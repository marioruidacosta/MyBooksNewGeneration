import { createContext } from 'react'

/** The part of Supabase Storage the site uses (so a test can bring its own). */
export interface CoverStorage {
  from(bucket: string): {
    upload(
      path: string,
      body: Blob,
      options: { upsert: boolean; contentType: string },
    ): PromiseLike<{ error: { message: string } | null }>
    createSignedUrls(
      paths: string[],
      expiresIn: number,
    ): PromiseLike<{
      data: { path: string | null; signedUrl: string; error: string | null }[] | null
      error: { message: string } | null
    }>
  }
}

export const BUCKET = 'covers'

/** Where a book's photo lives in the bucket. */
export const photoPath = (owner: string, bookId: string) => `${owner}/${bookId}.jpg`

/** Whose photos these are and where to read them; without it a photo shows as the generated cover. */
export type CoverSource = { owner: string; storage: CoverStorage }

export const CoverContext = createContext<CoverSource | null>(null)

const URL_SECONDS = 3600
/** Cached a little less than the link lives: a cover asks for a new one then (and when its picture fails to load). */
export const KEEP_MS = 55 * 60 * 1000

type Entry = { url: Promise<string>; expires: number }
const caches = new WeakMap<CoverStorage, Map<string, Entry>>()

let revision = 0
const listeners = new Set<() => void>()

/** Changes whenever a cached link is dropped (a new photo was uploaded). */
export const coverRevision = () => revision
export function onCoverRevision(cb: () => void): () => void {
  listeners.add(cb)
  return () => {
    listeners.delete(cb)
  }
}

type Waiting = { resolve: (url: string) => void; reject: (e: Error) => void }
const waiting = new WeakMap<CoverStorage, Map<string, Waiting[]>>()

/** Signs everything asked for in this turn with one request (a page of covers is one call, not one per book). */
function signLater(storage: CoverStorage, path: string): Promise<string> {
  let queue = waiting.get(storage)
  const first = !queue
  if (!queue) waiting.set(storage, (queue = new Map()))
  const result = new Promise<string>((resolve, reject) => {
    const askers = queue.get(path)
    if (askers) askers.push({ resolve, reject })
    else queue.set(path, [{ resolve, reject }])
  })
  if (first) {
    void Promise.resolve().then(async () => {
      const batch = waiting.get(storage)
      waiting.delete(storage)
      if (!batch) return
      const fail = (message: string) => {
        for (const askers of batch.values()) askers.forEach((a) => a.reject(new Error(message)))
      }
      try {
        const { data, error } = await storage.from(BUCKET).createSignedUrls([...batch.keys()], URL_SECONDS)
        if (error || !data) return fail(error?.message ?? 'no link')
        const done = new Set<string>()
        for (const item of data) {
          const askers = item.path === null ? undefined : batch.get(item.path)
          if (!askers || item.path === null) continue
          done.add(item.path)
          if (item.error || !item.signedUrl) askers.forEach((a) => a.reject(new Error(item.error ?? 'no link')))
          else askers.forEach((a) => a.resolve(item.signedUrl))
        }
        for (const [p, askers] of batch) if (!done.has(p)) askers.forEach((a) => a.reject(new Error('no link')))
      } catch (e) {
        fail(e instanceof Error ? e.message : String(e))
      }
    })
  }
  return result
}

/** A link to read [path] for an hour, the same one for about 55 minutes. Links asked for together are signed together. */
export function signedUrl(storage: CoverStorage, path: string, now: () => number = Date.now): Promise<string> {
  let cache = caches.get(storage)
  if (!cache) caches.set(storage, (cache = new Map()))
  const held = cache.get(path)
  if (held && held.expires > now()) return held.url
  const url = signLater(storage, path)
  const entry = { url, expires: now() + KEEP_MS }
  cache.set(path, entry)
  // A failure isn't kept: the next look asks again.
  url.catch(() => {
    if (cache.get(path) === entry) cache.delete(path)
  })
  return url
}

/** Drops the cached link without telling anyone (the link may have expired): the next look signs a new one. */
export function dropSignedUrl(storage: CoverStorage, path: string): void {
  caches.get(storage)?.delete(path)
}

/** Drops the cached link (the photo behind it changed) and tells the covers on screen to look again. */
export function forgetSignedUrl(storage: CoverStorage, path: string): void {
  caches.get(storage)?.delete(path)
  revision++
  listeners.forEach((l) => l())
}
