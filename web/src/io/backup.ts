import { strFromU8, strToU8, unzipSync, zipSync, type Zippable } from 'fflate'
import { stripBom } from './blobBytes'
import { STATUSES, type Book, type Quote, type Status } from '../sync/model'

/** As the app's `BackupFormatException`: the file isn't a version-1 MyBooks backup. */
export class BackupFormatException extends Error {
  constructor(message: string) {
    super(message)
    this.name = 'BackupFormatException'
  }
}

/** The reading goal of a year. */
export type BackupChallenge = { year: number; goal: number }

export type Backup = {
  /** Each with a new id (a quote's `bookId` is its book's). */
  books: Book[]
  quotes: Quote[]
  challenges: BackupChallenge[]
  /** The cover photos of a ZIP, by file name (a book's `coverUrl` says `cover:<name>`). */
  covers: Map<string, Blob>
  exportedAt: number
}

/** What a backup may hold once unzipped: a photo or the library is far smaller; this stops a "zip bomb". */
const MAX_ENTRY_BYTES = 50 * 1024 * 1024
const MAX_TOTAL_BYTES = 500 * 1024 * 1024
const DAMAGED = 'This backup file is damaged.'

const VERSION = 1
const LIBRARY = 'library.json'
const COVERS = 'covers/'
const PHOTO_REF = 'cover:'

/** Whether a photo's file name is plain: no folders, no `..` (as the app's `CoverStore.isSafeName`). */
const isSafeName = (name: string) => /^[A-Za-z0-9._-]+$/.test(name) && name !== '.' && name !== '..'

export const isZip = (b: Uint8Array) => b.length >= 4 && b[0] === 0x50 && b[1] === 0x4b && b[2] <= 0x07 && b[3] <= 0x08

const damaged = (): BackupFormatException => new BackupFormatException(DAMAGED)

const crcTable = Uint32Array.from({ length: 256 }, (_, n) => {
  let c = n
  for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1
  return c >>> 0
})

/** CRC-32 (IEEE 802.3) as ZIP stores it. */
function crc32(data: Uint8Array): number {
  let c = 0xffffffff
  for (const b of data) c = crcTable[(c ^ b) & 0xff] ^ (c >>> 8)
  return (c ^ 0xffffffff) >>> 0
}

/** The CRC and size each entry of the ZIP's central directory promises, by name (as the app's `Zip.read`). */
function centralDirectory(zip: Uint8Array): Map<string, { crc: number; size: number }> {
  const v = new DataView(zip.buffer, zip.byteOffset, zip.byteLength)
  let end = -1
  for (let i = zip.length - 22; i >= Math.max(0, zip.length - 22 - 0xffff); i--) {
    if (v.getUint32(i, true) === 0x06054b50) {
      end = i
      break
    }
  }
  if (end < 0) throw damaged()
  const count = v.getUint16(end + 10, true)
  let pos = v.getUint32(end + 16, true)
  const out = new Map<string, { crc: number; size: number }>()
  for (let i = 0; i < count; i++) {
    if (pos + 46 > zip.length || v.getUint32(pos, true) !== 0x02014b50) throw damaged()
    const nameLength = v.getUint16(pos + 28, true)
    const name = strFromU8(zip.subarray(pos + 46, pos + 46 + nameLength))
    out.set(name, { crc: v.getUint32(pos + 16, true), size: v.getUint32(pos + 24, true) })
    pos += 46 + nameLength + v.getUint16(pos + 30, true) + v.getUint16(pos + 32, true)
  }
  return out
}

/** The entries of a ZIP, each checked against its CRC and size, and refused past the size caps. */
function unzipChecked(zip: Uint8Array, limits: BackupLimits): Record<string, Uint8Array> {
  const maxEntry = limits.maxEntryBytes ?? MAX_ENTRY_BYTES
  const maxTotal = limits.maxTotalBytes ?? MAX_TOTAL_BYTES
  let total = 0
  let entries: Record<string, Uint8Array>
  let promised: Map<string, { crc: number; size: number }>
  try {
    promised = centralDirectory(zip)
    entries = unzipSync(zip, {
      filter: (file) => {
        // The sizes come from the archive, so a huge entry is refused before it is inflated.
        total += file.originalSize
        if (file.originalSize > maxEntry || total > maxTotal) throw damaged()
        return true
      },
    })
  } catch (e) {
    throw e instanceof BackupFormatException ? e : damaged()
  }
  for (const [name, data] of Object.entries(entries)) {
    const p = promised.get(name)
    if (!p) {
      if (data.length === 0) continue
      throw damaged()
    }
    if (data.length !== p.size || crc32(data) !== p.crc) throw damaged()
  }
  return entries
}

export type BackupLimits = { maxEntryBytes?: number; maxTotalBytes?: number }

const bad = (cause?: unknown): BackupFormatException =>
  new BackupFormatException(cause instanceof Error && cause.message.startsWith('Unsupported') ? cause.message : 'Not a MyBooks backup file')

type Obj = Record<string, unknown>

const isObj = (v: unknown): v is Obj => typeof v === 'object' && v !== null && !Array.isArray(v)

/** A field the app's decoder would refuse when mistyped: absent or null gives the default. */
function text(o: Obj, k: string): string | null {
  const v = o[k]
  if (v === undefined || v === null) return null
  if (typeof v !== 'string') throw bad()
  return v
}

function int(o: Obj, k: string): number | null {
  const v = o[k]
  if (v === undefined || v === null) return null
  if (typeof v !== 'number' || !Number.isInteger(v)) throw bad()
  return v
}

function list(o: Obj, k: string): unknown[] {
  const v = o[k]
  if (v === undefined || v === null) return []
  if (!Array.isArray(v)) throw bad()
  return v
}

function item(v: unknown): Obj {
  if (!isObj(v)) throw bad()
  return v
}

/**
 * Reads a backup: the app's `.zip` (`library.json` and `covers/<name>`) or its older plain `.json`, version 1.
 * Throws [BackupFormatException] before anything is used if it isn't one. Unknown fields are ignored and missing ones
 * take their defaults, so older backups stay readable.
 */
export function parseBackup(fileBytes: Uint8Array, limits: BackupLimits = {}): Backup {
  const covers = new Map<string, Blob>()
  let library: string
  if (isZip(fileBytes)) {
    const entries = unzipChecked(fileBytes, limits)
    const lib = entries[LIBRARY]
    if (!lib) throw bad()
    library = strFromU8(lib)
    for (const [name, data] of Object.entries(entries)) {
      const photo = name.slice(COVERS.length)
      if (name.startsWith(COVERS) && isSafeName(photo)) covers.set(photo, new Blob([data as BlobPart], { type: 'image/jpeg' }))
    }
  } else {
    library = strFromU8(fileBytes)
  }

  let file: unknown
  try {
    file = JSON.parse(stripBom(library))
  } catch (e) {
    throw bad(e)
  }
  if (!isObj(file)) throw bad()
  if (file.version !== VERSION) {
    throw new BackupFormatException(
      typeof file.version === 'number' ? `Unsupported backup version ${String(file.version)}` : 'Not a MyBooks backup file',
    )
  }
  const books: Book[] = []
  const quotes: Quote[] = []
  for (const raw of list(file, 'books')) {
    const o = item(raw)
    const status = text(o, 'status') ?? 'TO_READ'
    if (!(STATUSES as readonly string[]).includes(status)) throw bad()
    const id = crypto.randomUUID()
    books.push({
      id,
      isbn: text(o, 'isbn'),
      title: text(o, 'title') ?? '',
      authors: text(o, 'authors') ?? '',
      publisher: text(o, 'publisher'),
      publishedYear: int(o, 'publishedYear'),
      pageCount: int(o, 'pageCount'),
      coverUrl: text(o, 'coverUrl'),
      description: text(o, 'description'),
      status: status as Status,
      rating: int(o, 'rating'),
      notes: text(o, 'notes') ?? '',
      dateAdded: int(o, 'dateAdded') ?? 0,
      dateRead: int(o, 'dateRead'),
      dateStarted: int(o, 'dateStarted'),
      currentPage: int(o, 'currentPage'),
      progressPercent: int(o, 'progressPercent'),
      review: text(o, 'review') ?? '',
      tags: list(o, 'tags').map((t) => {
        if (typeof t !== 'string') throw bad()
        return t
      }),
      series: text(o, 'series'),
      seriesNumber: text(o, 'seriesNumber'),
      lentTo: text(o, 'lentTo'),
      lentOn: int(o, 'lentOn'),
      deleted: false,
    })
    for (const rawQuote of list(o, 'quotes')) {
      const q = item(rawQuote)
      quotes.push({
        id: crypto.randomUUID(), bookId: id, text: text(q, 'text') ?? '', page: int(q, 'page'),
        dateAdded: int(q, 'dateAdded') ?? 0, position: text(q, 'position'), deleted: false,
      })
    }
  }
  const challenges = list(file, 'challenges').map((raw) => {
    const c = item(raw)
    const year = int(c, 'year')
    const goal = int(c, 'goal')
    if (year === null || goal === null) throw bad()
    return { year, goal }
  })
  return { books, quotes, challenges, covers, exportedAt: int(file, 'exportedAt') ?? 0 }
}

/** What a backup is made of: the live rows, and each book's photo bytes by book id. */
export type BackupData = {
  books: Book[]
  quotes: Quote[]
  challenges: BackupChallenge[]
  covers: Map<string, Uint8Array>
}

/** A book's photo in the ZIP: the name is the book's id, which the app accepts as a file name. */
export const photoName = (bookId: string) => `${bookId}.jpg`

/**
 * The library as the app's backup `.zip`: `library.json` (`{"version":1,...}`, as `BackupSerializer.encode`) and each
 * photo as `covers/<name>`. A book whose photo is not in [BackupData.covers] is written with no cover. Deleted books and
 * their quotes are left out. What the site doesn't keep (share ids, ebooks, bookmarks, hidden suggestions) is written
 * empty, as the app does for a book without them.
 */
export function writeBackup(data: BackupData, exportedAt: number): Blob {
  const live = data.books.filter((b) => !b.deleted)
  const entries: Zippable = {}
  const library = {
    version: VERSION,
    exportedAt,
    books: live.map((b) => {
      let coverUrl = b.coverUrl
      if (coverUrl === 'photo') {
        const bytes = data.covers.get(b.id)
        if (bytes) {
          // A JPEG is already compressed: stored as it is.
          entries[COVERS + photoName(b.id)] = [bytes, { level: 0 }]
          coverUrl = PHOTO_REF + photoName(b.id)
        } else coverUrl = null
      }
      return {
        isbn: b.isbn, title: b.title, authors: b.authors, publisher: b.publisher, publishedYear: b.publishedYear,
        pageCount: b.pageCount, coverUrl, description: b.description, status: b.status, rating: b.rating,
        notes: b.notes, dateAdded: b.dateAdded, dateRead: b.dateRead, dateStarted: b.dateStarted,
        currentPage: b.currentPage, progressPercent: b.progressPercent, review: b.review, shareId: null,
        shareExcluded: false, tags: b.tags, series: b.series, seriesNumber: b.seriesNumber, lentTo: b.lentTo,
        lentOn: b.lentOn,
        quotes: data.quotes
          .filter((q) => !q.deleted && q.bookId === b.id)
          .map((q) => ({ text: q.text, page: q.page, dateAdded: q.dateAdded, position: q.position })),
        ebook: null,
        bookmarks: [],
      }
    }),
    challenges: data.challenges.map((c) => ({ year: c.year, goal: c.goal })),
    hiddenSuggestions: [] as string[],
  }
  const files: Zippable = { [LIBRARY]: strToU8(JSON.stringify(library)), ...entries }
  return new Blob([zipSync(files) as BlobPart], { type: 'application/zip' })
}

/** The photo name a book's `cover:<name>` reference points at, or null for a web address. */
export function photoRefName(coverUrl: string | null): string | null {
  if (!coverUrl?.startsWith(PHOTO_REF)) return null
  const name = coverUrl.slice(PHOTO_REF.length)
  return isSafeName(name) ? name : null
}
