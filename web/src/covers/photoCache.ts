import { openDB, type IDBPDatabase } from 'idb'
import { blobBytes } from '../io/blobBytes'

/**
 * Cover photos kept in this browser, one database per account, so a photo seen once shows at once and works offline.
 * A signed link changes on every launch, so the browser's own cache can't do this.
 *
 * Each photo is kept for one [stamp]: the time of the book's `cover_url`, which moves when the photo changes. A kept
 * photo is also compared with the cloud's about once a day, in case the time moved before the new file arrived.
 */
const CHECK_EVERY_MS = 24 * 60 * 60 * 1000

/** Bytes rather than a Blob: some versions of Safari can't store a Blob in IndexedDB. */
type Row = { path: string; stamp: number; type: string; bytes: ArrayBuffer; checkedAt: number }
type Held = { stamp: number; url: string; checkedAt: number }

export type KeptPhoto = { url: string; due: boolean }

const STORE = 'photos'
const databases = new Map<string, Promise<IDBPDatabase | null>>()
/** The pictures already read this session, so a cover that comes back on screen needs no database read. */
const held = new Map<string, Held>()

const ownerOf = (path: string) => path.slice(0, path.indexOf('/'))
const isOwn = (owner: string, path: string) => owner !== '' && ownerOf(path) === owner

function database(owner: string): Promise<IDBPDatabase | null> {
  let db = databases.get(owner)
  if (!db) {
    db = (async () => {
      try {
        return await openDB(`mybooks-covers-${owner}`, 1, {
          upgrade: (d) => void d.createObjectStore(STORE, { keyPath: 'path' }),
        })
      } catch {
        // Private browsing, no room, no IndexedDB: covers simply come from the network, as they did.
        return null
      }
    })()
    databases.set(owner, db)
  }
  return db
}

function hold(path: string, stamp: number, blob: Blob, checkedAt: number): string {
  // The previous address is not revoked: a cover on screen may still be using it.
  const url = URL.createObjectURL(blob)
  held.set(path, { stamp, url, checkedAt })
  return url
}

const keptPhoto = (h: Held, now: number): KeptPhoto => ({ url: h.url, due: now - h.checkedAt > CHECK_EVERY_MS })

async function toRow(path: string, stamp: number, blob: Blob, checkedAt: number): Promise<Row> {
  const bytes = await blobBytes(blob)
  const copy = bytes.buffer.slice(bytes.byteOffset, bytes.byteOffset + bytes.byteLength) as ArrayBuffer
  return { path, stamp, type: blob.type || 'image/jpeg', bytes: copy, checkedAt }
}

/** The picture for this version when it was read already this session; null otherwise. */
export function keptNow(path: string, stamp: number, now: () => number = Date.now): KeptPhoto | null {
  const h = held.get(path)
  return h && h.stamp === stamp ? keptPhoto(h, now()) : null
}

/** The picture kept for this version of [path], or null. Never throws. */
export async function savedPhoto(owner: string, path: string, stamp: number, now: () => number = Date.now): Promise<KeptPhoto | null> {
  if (!isOwn(owner, path)) return null
  const inMemory = keptNow(path, stamp, now)
  if (inMemory) return inMemory
  const db = await database(owner)
  if (!db) return null
  try {
    const row = (await db.get(STORE, path)) as Row | undefined
    if (!row || row.stamp !== stamp) return null
    hold(path, stamp, new Blob([row.bytes], { type: row.type }), row.checkedAt)
    return keptNow(path, stamp, now)
  } catch {
    return null
  }
}

/** Keeps [blob] as this version of [path]. Never throws: a photo that can't be kept just loads from the network next time. */
export async function keepPhoto(owner: string, path: string, stamp: number, blob: Blob, now: () => number = Date.now): Promise<void> {
  if (!isOwn(owner, path)) return
  const db = await database(owner)
  if (!db) return
  try {
    const at = now()
    await db.put(STORE, await toRow(path, stamp, blob, at))
    hold(path, stamp, blob, at)
  } catch {
    // No room: nothing is kept.
  }
}

/**
 * The cloud's picture, after a check: the same one is only marked as checked (null); a different one replaces what is
 * kept, and its new address is returned.
 */
export async function replacePhoto(owner: string, path: string, stamp: number, blob: Blob, now: () => number = Date.now): Promise<string | null> {
  if (!isOwn(owner, path)) return null
  const db = await database(owner)
  if (!db) return null
  try {
    const at = now()
    const next = await toRow(path, stamp, blob, at)
    const row = (await db.get(STORE, path)) as Row | undefined
    if (row && row.stamp === stamp && sameBytes(row.bytes, next.bytes)) {
      await db.put(STORE, { ...row, checkedAt: at })
      const h = held.get(path)
      if (h && h.stamp === stamp) held.set(path, { ...h, checkedAt: at })
      return null
    }
    await db.put(STORE, next)
    return hold(path, stamp, blob, at)
  } catch {
    return null
  }
}

function sameBytes(a: ArrayBuffer, b: ArrayBuffer): boolean {
  if (a.byteLength !== b.byteLength) return false
  const x = new Uint8Array(a)
  const y = new Uint8Array(b)
  return x.every((v, i) => v === y[i])
}

/** Forgets [path] (a new photo was uploaded, or the kept one can't be shown). Never throws. */
export async function dropPhoto(path: string): Promise<void> {
  held.delete(path)
  const db = await database(ownerOf(path))
  try {
    await db?.delete(STORE, path)
  } catch {
    // Its version no longer matches anyway, or the next check replaces it.
  }
}
