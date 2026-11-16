import { shrinkToJpeg, uploadCover, MAX_BYTES, MAX_SIDE, type ImageDecoder } from '../covers/coverUpload'
import type { CoverStorage } from '../covers/storage'
import { bookToFields, rowToBook } from '../sync/codec'
import type { Book, Quote, Status, SyncRow } from '../sync/model'
import type { LibraryStore } from '../sync/store'
import { combineRows, type FieldTimes } from '../sync/twin'
import { findInLibrary } from '../text/keys'
import type { Backup, BackupChallenge } from './backup'
import { photoRefName } from './backup'
import { cleanTags, MAX_REVIEW_LENGTH, normalizeIsbn } from './goodreads'

const MAX_NAME_LENGTH = 100
const MAX_QUOTE_LENGTH = 2000
const MAX_GOAL = 999

/** A book read from a file, and when each of its fields counts as last changed (by the server's field name). */
export type Incoming = { book: Book; times: FieldTimes }

/** What a file holds. [invent]: a book restored as Read with no date gets today's (as the app does for a backup). */
export type ImportSource = {
  items: Incoming[]
  quotes: Quote[]
  challenges: BackupChallenge[]
  covers: Map<string, Blob>
  invent: boolean
}

const allFields = (b: Book, time: number): FieldTimes =>
  Object.fromEntries(Object.keys(bookToFields(b)).map((f) => [f, time]))

/**
 * Goodreads rows. A row without field times counts as older than anything edited since, except for what the shelf
 * says: the app's Goodreads import takes the shelf (status), the date read, a rating and a review, and the private
 * notes joined; every other detail only fills in where the library's copy is empty. So those fields count as
 * changed at [now] and the rest as the oldest.
 */
export function goodreadsItems(books: Book[], now: number): ImportSource {
  const shelf = ['status', 'date_read', 'rating', 'review', 'notes']
  return {
    items: books.map((book) => ({
      book,
      times: { ...allFields(book, 0), ...Object.fromEntries(shelf.map((f) => [f, now])) },
    })),
    quotes: [], challenges: [], covers: new Map(), invent: false,
  }
}

/** A backup's books, each field as of the time the backup was made. */
export function backupItems(backup: Backup): ImportSource {
  return {
    items: backup.books.map((book) => ({ book, times: allFields(book, backup.exportedAt) })),
    quotes: backup.quotes, challenges: backup.challenges, covers: backup.covers, invent: true,
  }
}

export type Merge = { id: string; changes: Record<string, unknown> }

export type Plan = {
  /** Books not in the library yet (with their final ids). */
  added: Book[]
  /** Books already there (a book is counted even when the file adds nothing to it). */
  merged: Merge[]
  skipped: number
  quotes: Quote[]
  quoteCount: number
  goals: BackupChallenge[]
  /** Photos to upload once their books exist. */
  uploads: { bookId: string; blob: Blob }[]
}

/** "N new · M already in your library (merged) · K skipped". */
export const previewText = (plan: Plan): string =>
  `${String(plan.added.length)} new · ${String(plan.merged.length)} already in your library (merged) · ${String(plan.skipped)} skipped`

/** The library's rules for a book (the app's `validate`); null when the book can't be kept. */
function clean(b: Book): Book | null {
  const title = b.title.trim()
  if (title === '') return null
  if (b.rating !== null && !(b.rating >= 1 && b.rating <= 5)) return null
  if (b.review.length > MAX_REVIEW_LENGTH) return null
  let isbn: string | null = null
  if (b.isbn !== null && b.isbn.trim() !== '') {
    isbn = normalizeIsbn(b.isbn)
    if (isbn === null) return null
  }
  const series = b.series?.trim().slice(0, MAX_NAME_LENGTH) || null
  const lentTo = b.lentTo?.trim().slice(0, MAX_NAME_LENGTH) || null
  // A number only means something within a series.
  const number = b.seriesNumber?.trim().replace(/^#/, '').trim().slice(0, 10) || null
  return {
    ...b,
    title,
    isbn,
    currentPage: b.currentPage !== null && b.currentPage >= 0 ? b.currentPage : null,
    progressPercent: b.progressPercent === null ? null : Math.min(100, Math.max(0, b.progressPercent)),
    tags: cleanTags(b.tags),
    series,
    seriesNumber: series !== null ? number : null,
    lentTo,
    lentOn: lentTo !== null ? b.lentOn : null,
  }
}

/** The dates a status goes with (the app's `withStatusDates`); [previous] is the status the book had. */
function statusDates(b: Book, previous: Status | null, now: number): Book {
  switch (b.status) {
    case 'READING':
      return { ...b, dateRead: null, dateStarted: b.dateStarted ?? (previous !== 'READING' ? now : null) }
    case 'READ': {
      const read = b.dateRead ?? (previous !== 'READ' ? now : null)
      return { ...b, dateRead: read, dateStarted: b.dateStarted !== null && (read === null || b.dateStarted <= read) ? b.dateStarted : null }
    }
    default:
      return { ...b, dateRead: null, dateStarted: null, currentPage: null, progressPercent: null }
  }
}

const rowOf = (b: Book, times: FieldTimes): SyncRow => ({ table: 'books', id: b.id, fields: bookToFields(b), times })

/**
 * What importing [source] would do, without doing it. Each book is the library's copy when it has one (the same ISBN,
 * or else the same title and first author), and then the two are combined by the twin rule (`combineRows`);
 * otherwise it is new. A book that can't be kept (no title, a bad rating or ISBN, a review over the limit) or that
 * repeats an earlier row of the file is skipped.
 */
export function planImport(source: ImportSource, store: Pick<LibraryStore, 'books' | 'bookTimes' | 'quotes' | 'goal'>, now: number): Plan {
  const library = store.books()
  const pool = [...library]
  const fresh = new Set<string>()
  const seenIsbns = new Set<string>()
  const plan: Plan = { added: [], merged: [], skipped: 0, quotes: [], quoteCount: 0, goals: [], uploads: [] }
  /** The library's id for each of the file's books (the book's own when it is new). */
  const target = new Map<string, string>()
  const libraryIds = new Set(library.map((b) => b.id))

  for (const { book: raw, times } of source.items) {
    // A photo travels by its name: it is used only when the file has it.
    const photoName = photoRefName(raw.coverUrl)
    const photo = photoName !== null ? source.covers.get(photoName) : undefined
    const book = clean({ ...raw, coverUrl: photo ? 'photo' : photoName !== null ? null : raw.coverUrl })
    if (book === null || (book.isbn !== null && seenIsbns.has(book.isbn))) {
      plan.skipped++
      continue
    }
    const twin = findInLibrary(book, pool)
    if (twin && fresh.has(twin.id)) {
      plan.skipped++ // the file has this book twice
      continue
    }
    if (book.isbn !== null) seenIsbns.add(book.isbn)

    if (twin && libraryIds.has(twin.id)) {
      const mine = twin
      target.set(raw.id, mine.id)
      // The library's date added stays, as in the app.
      const incoming = { ...book, id: mine.id, dateAdded: mine.dateAdded }
      const combined = combineRows(rowOf(mine, store.bookTimes(mine.id)), rowOf(incoming, times))
      const merged = statusDates(rowToBook(combined), book.status, now)
      const before = bookToFields(mine)
      const after = bookToFields(merged)
      const changes: Record<string, unknown> = {}
      for (const f of Object.keys(after)) {
        if (f !== 'deleted' && after[f] !== before[f]) changes[f] = after[f]
      }
      plan.merged.push({ id: mine.id, changes })
      if (photo && mine.coverUrl === null) plan.uploads.push({ bookId: mine.id, blob: photo })
      continue
    }

    const added = statusDates(
      { ...book, dateAdded: book.dateAdded > 0 ? book.dateAdded : now },
      source.invent ? null : book.status,
      now,
    )
    // Stored without the marker until its photo is up.
    plan.added.push({ ...added, coverUrl: photo ? null : added.coverUrl })
    pool.push(added)
    fresh.add(added.id)
    target.set(raw.id, added.id)
    if (photo) plan.uploads.push({ bookId: added.id, blob: photo })
  }

  // Quotes go to their book's copy; one the book already has (the same text) is not added twice.
  const have = new Map<string, Set<string>>()
  for (const q of source.quotes) {
    const bookId = target.get(q.bookId)
    if (bookId === undefined) continue
    const text = q.text.trim().slice(0, MAX_QUOTE_LENGTH).trim()
    if (text === '') continue
    let texts = have.get(bookId)
    if (!texts) have.set(bookId, (texts = new Set(store.quotes(bookId).map((x) => x.text))))
    if (texts.has(text)) continue
    texts.add(text)
    plan.quotes.push({
      ...q, bookId, text, page: q.page !== null && q.page > 0 ? q.page : null, dateAdded: q.dateAdded > 0 ? q.dateAdded : now,
    })
  }
  plan.quoteCount = plan.quotes.length

  // A goal replaces the same year's goal.
  for (const c of source.challenges) {
    if (c.goal >= 1 && c.goal <= MAX_GOAL && store.goal(c.year) !== c.goal) plan.goals.push(c)
  }
  return plan
}

export type ApplyDeps = {
  /** For the photos of a backup. */
  owner?: string
  storage?: CoverStorage
  decode?: ImageDecoder
}

/**
 * Writes the plan. The store queues every change and sends them [PUSH_BATCH] at a time (the same pass as any edit), so
 * nothing here batches. Photos go up last, one by one; one that fails is counted and its book stays as imported.
 */
export async function applyPlan(plan: Plan, store: LibraryStore, deps: ApplyDeps): Promise<{ coverFailures: number }> {
  for (const book of plan.added) store.create(book)
  for (const m of plan.merged) if (Object.keys(m.changes).length > 0) store.edit('books', m.id, m.changes)
  for (const q of plan.quotes) store.createQuote(q)
  for (const g of plan.goals) store.edit('challenges', String(g.year), { goal: g.goal })

  let coverFailures = 0
  for (const { bookId, blob } of plan.uploads) {
    try {
      if (!deps.owner || !deps.storage) throw new Error('No place to keep photos.')
      const jpeg = await shrinkToJpeg(blob, MAX_SIDE, MAX_BYTES, deps.decode)
      await uploadCover(deps.owner, bookId, jpeg, store, deps.storage)
    } catch {
      coverFailures++
    }
  }
  return { coverFailures }
}
