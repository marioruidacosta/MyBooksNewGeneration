import { createContext } from 'react'

/** The part of Supabase Storage the site uses (so a test can bring its own). */
export interface CoverStorage {
  from(bucket: string): {
    upload(
      path: string,
      body: Blob,
      options: { upsert: boolean; contentType: string },
    ): PromiseLike<{ error: { message: string } | null }>
    createSignedUrl(
      path: string,
      expiresIn: number,
    ): PromiseLike<{ data: { signedUrl: string } | null; error: { message: string } | null }>
  }
}

export const BUCKET = 'covers'

/** Where a book's photo lives in the bucket. */
export const photoPath = (owner: string, bookId: string) => `${owner}/${bookId}.jpg`

/** Whose photos these are and where to read them; without it a photo shows as the generated cover. */
export type CoverSource = { owner: string; storage: CoverStorage }

export const CoverContext = createContext<CoverSource | null>(null)

const URL_SECONDS = 3600
/** Cached a little less than the link lives. */
const KEEP_MS = 55 * 60 * 1000

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

/** A link to read [path] for an hour, the same one for about 55 minutes. */
export function signedUrl(storage: CoverStorage, path: string, now: () => number = Date.now): Promise<string> {
  let cache = caches.get(storage)
  if (!cache) caches.set(storage, (cache = new Map()))
  const held = cache.get(path)
  if (held && held.expires > now()) return held.url
  const url = Promise.resolve(storage.from(BUCKET).createSignedUrl(path, URL_SECONDS)).then(({ data, error }) => {
    if (error || !data) throw new Error(error?.message ?? 'no link')
    return data.signedUrl
  })
  const entry = { url, expires: now() + KEEP_MS }
  cache.set(path, entry)
  // A failure isn't kept: the next look asks again.
  url.catch(() => {
    if (cache.get(path) === entry) cache.delete(path)
  })
  return url
}

/** Drops the cached link (the photo behind it changed) and tells the covers on screen to look again. */
export function forgetSignedUrl(storage: CoverStorage, path: string): void {
  caches.get(storage)?.delete(path)
  revision++
  listeners.forEach((l) => l())
}
