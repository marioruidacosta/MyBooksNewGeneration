import { strFromU8 } from 'fflate'
import { photoPath, signedUrl, type CoverStorage } from '../covers/storage'
import type { LibraryStore } from '../sync/store'
import { backupItems, goodreadsItems, type ImportSource } from './importPlan'
import { isZip, parseBackup, writeBackup } from './backup'
import { GoodreadsFormatException, parseGoodreads, writeGoodreads } from './goodreads'
import { blobBytes, stripBom } from './blobBytes'

/** Hands a file to the person: the browser saves it. A test brings its own. */
export type Download = (name: string, file: Blob) => void

export const browserDownload: Download = (name, file) => {
  const url = URL.createObjectURL(file)
  const link = document.createElement('a')
  link.href = url
  link.download = name
  document.body.appendChild(link)
  link.click()
  link.remove()
  setTimeout(() => URL.revokeObjectURL(url), 10_000)
}

/** A book's photo bytes, or null when it can't be had. */
export type GetPhoto = (bookId: string) => Promise<Uint8Array | null>

/** The photos kept in the account's storage, read through a signed link. */
export function storagePhotos(owner: string, storage: CoverStorage, fetchIt: typeof fetch = (u, i) => fetch(u, i)): GetPhoto {
  return async (bookId) => {
    try {
      const url = await signedUrl(storage, photoPath(owner, bookId))
      const res = await fetchIt(url)
      if (!res.ok) return null
      return new Uint8Array(await res.arrayBuffer())
    } catch {
      return null
    }
  }
}

/** The library in Goodreads' CSV. */
export function goodreadsExport(store: LibraryStore): { name: string; file: Blob } {
  return { name: 'mybooks-goodreads.csv', file: new Blob([writeGoodreads(store.books())], { type: 'text/csv' }) }
}

const pad = (n: number) => String(n).padStart(2, '0')

/** The library as the app's backup ZIP, with the cover photos that can be fetched. */
export async function backupExport(
  store: LibraryStore,
  now: number,
  getPhoto: GetPhoto,
): Promise<{ name: string; file: Blob }> {
  const books = store.books()
  const covers = new Map<string, Uint8Array>()
  for (const b of books) {
    if (b.coverUrl !== 'photo') continue
    const bytes = await getPhoto(b.id)
    if (bytes) covers.set(b.id, bytes)
  }
  const file = writeBackup(
    { books, quotes: books.flatMap((b) => store.quotes(b.id)), challenges: store.goals(), covers },
    now,
  )
  const d = new Date(now)
  return { name: `mybooks-backup-${String(d.getFullYear())}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}.zip`, file }
}

/** What the person chose to import: a backup (a ZIP, or JSON) or a Goodreads CSV, told apart by what's in it. */
export async function readImportFile(file: Blob, now: number): Promise<ImportSource> {
  const bytes = await blobBytes(file)
  const text = isZip(bytes) ? '' : stripBom(strFromU8(bytes)).trimStart()
  if (isZip(bytes) || text.startsWith('{')) return backupItems(parseBackup(bytes))
  try {
    return goodreadsItems(parseGoodreads(strFromU8(bytes)), now)
  } catch (e) {
    if (e instanceof GoodreadsFormatException) throw new Error("That isn't a Goodreads export.")
    throw e
  }
}

