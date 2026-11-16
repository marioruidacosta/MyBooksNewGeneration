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

const VERSION = 1
const LIBRARY = 'library.json'
const COVERS = 'covers/'
const PHOTO_REF = 'cover:'

/** Whether a photo's file name is plain: no folders, no `..` (as the app's `CoverStore.isSafeName`). */
const isSafeName = (name: string) => /^[A-Za-z0-9._-]+$/.test(name) && name !== '.' && name !== '..'

export const isZip = (b: Uint8Array) => b.length >= 4 && b[0] === 0x50 && b[1] === 0x4b && b[2] <= 0x07 && b[3] <= 0x08

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
export function parseBackup(fileBytes: Uint8Array): Backup {
  const covers = new Map<string, Blob>()
  let library: string
  if (isZip(fileBytes)) {
    let entries: Record<string, Uint8Array>
    try {
      entries = unzipSync(fileBytes)
    } catch (e) {
      throw bad(e)
    }
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
