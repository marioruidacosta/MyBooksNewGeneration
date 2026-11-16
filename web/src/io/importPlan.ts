import { shrinkToJpeg, uploadCover, MAX_BYTES, MAX_SIDE, type ImageDecoder } from '../covers/coverUpload'
import type { CoverStorage } from '../covers/storage'
import { bookToFields, rowToBook } from '../sync/codec'
import type { Book, Quote, Status, SyncRow } from '../sync/model'
import type { LibraryStore } from '../sync/store'
import { combineRows, type FieldTimes } from '../sync/twin'
import { catalogTitleKey, firstAuthorKey } from '../text/keys'
import type { Backup, BackupChallenge } from './backup'
import { photoRefName } from './backup'
import { cleanTags, MAX_REVIEW_LENGTH, normalizeIsbn } from './goodreads'

const MAX_NAME_LENGTH = 100
const MAX_QUOTE_LENGTH = 2000
const MAX_GOAL = 999

/** A book read from a file, and when each of its fields counts as last changed (by the server's field name). */
export type Incoming = { book: Book; times: FieldTimes }

/** What a file holds. [keepRead]: a Goodreads file, which never un-reads a book the library has as Read. */
export type ImportSource = {
  items: Incoming[]
  quotes: Quote[]
  challenges: BackupChallenge[]
  covers: Map<string, Blob>
  keepRead: boolean
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
    quotes: [], challenges: [], covers: new Map(), keepRead: true,
  }
}

/** A backup's books, each field as of the time the backup was made. */
export function backupItems(backup: Backup): ImportSource {
  return {
    items: backup.books.map((book) => ({ book, times: allFields(book, backup.exportedAt) })),
    quotes: backup.quotes, challenges: backup.challenges, covers: backup.covers, keepRead: false,
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

/** The books of a library by ISBN and by title and first author, built once (as the app's `TitleIndex`). */
class BookIndex {
  private byIsbn = new Map<string, Book>()
  private byKey = new Map<string, Book[]>()

  constructor(books: Book[]) {
    books.forEach((b) => this.add(b))
  }

  private static key(b: Book) {
    return `${catalogTitleKey(b.title)}|${firstAuthorKey(b.authors)}`
  }

  add(b: Book) {
    if (b.isbn !== null) this.byIsbn.set(b.isbn, b)
    const k = BookIndex.key(b)
    const list = this.byKey.get(k)
    if (list) list.push(b)
    else this.byKey.set(k, [b])
  }

  replace(old: Book, next: Book) {
    if (old.isbn !== null && this.byIsbn.get(old.isbn)?.id === old.id) this.byIsbn.delete(old.isbn)
    const k = BookIndex.key(old)
    const list = this.byKey.get(k)?.filter((b) => b.id !== old.id)
    if (list) this.byKey.set(k, list)
    this.add(next)
  }

  /** The same ISBN, or the same title and first author where one of the two has no ISBN (two ISBNs are two books). */
  find(b: Book): Book | undefined {
    if (b.isbn !== null) {
      const hit = this.byIsbn.get(b.isbn)
      if (hit) return hit
    }
    return this.byKey.get(BookIndex.key(b))?.find((x) => b.isbn === null || x.isbn === null)
  }
}

/**
 * What importing [source] would do, without doing it. Each book is the library's copy when it has one (the same ISBN,
 * or the same title and first author where one side has no ISBN: two ISBNs are two books), and then the two are
 * combined by the twin rule (`combineRows`); otherwise it is new. Rows of the file that match the same library book
 * are merged into it once. A book that can't be kept (no title, a bad rating or ISBN, a review over the limit) or that
 * repeats an earlier new row of the file is skipped.
 */
export function planImport(source: ImportSource, store: Pick<LibraryStore, 'books' | 'bookTimes' | 'quotes' | 'goal'>, now: number): Plan {
  const library = store.books()
  const index = new BookIndex(library)
  const libraryIds = new Set(library.map((b) => b.id))
  const fresh = new Set<string>()
  const seenIsbns = new Set<string>()
  const plan: Plan = { added: [], merged: [], skipped: 0, quotes: [], quoteCount: 0, goals: [], uploads: [] }
  /** The library's id for each of the file's books (the book's own when it is new). */
  const target = new Map<string, string>()
  /** Each merged library book: as it was, as combined so far, and its entry in the plan. */
  const running = new Map<string, { original: Book; row: SyncRow; merge: Merge; upload: number }>()

  for (const { book: raw, times } of source.items) {
    // A photo travels by its name: it is used only when the file has it.
    const photoName = photoRefName(raw.coverUrl)
    const photo = photoName !== null ? source.covers.get(photoName) : undefined
    const book = clean({ ...raw, coverUrl: photoName !== null ? null : raw.coverUrl })
    if (book === null || (book.isbn !== null && seenIsbns.has(book.isbn))) {
      plan.skipped++
      continue
    }
    const twin = index.find(book)
    if (twin && fresh.has(twin.id)) {
      plan.skipped++ // the file has this book twice
      continue
    }
    if (book.isbn !== null) seenIsbns.add(book.isbn)

    if (twin && libraryIds.has(twin.id)) {
      const state = running.get(twin.id)
      const original = state?.original ?? twin
      const keepRow = state?.row ?? rowOf(twin, store.bookTimes(twin.id))
      const current = state ? rowToBook(state.row) : twin
      // The library's date added stays, as in the app; a photo is set only by its upload.
      const incoming: Book = { ...book, id: twin.id, dateAdded: current.dateAdded, coverUrl: book.coverUrl }
      // An old Goodreads export doesn't un-read a book.
      if (source.keepRead && current.status === 'READ' && book.status !== 'READ') {
        Object.assign(incoming, {
          status: current.status, dateRead: current.dateRead, dateStarted: current.dateStarted,
          currentPage: current.currentPage, progressPercent: current.progressPercent,
        })
      }
      const combined = combineRows(keepRow, rowOf(incoming, times))
      const merged = statusDates(rowToBook(combined), incoming.status, now)
      const row = rowOf(merged, combined.times)
      const before = bookToFields(original)
      const after = bookToFields(merged)
      const changes: Record<string, unknown> = {}
      for (const f of Object.keys(after)) {
        if (f !== 'deleted' && after[f] !== before[f]) changes[f] = after[f]
      }
      if (state) {
        state.merge.changes = changes
        state.row = row
      } else {
        const merge = { id: twin.id, changes }
        plan.merged.push(merge)
        running.set(twin.id, { original, row, merge, upload: -1 })
      }
      const held = running.get(twin.id) as { upload: number }
      if (photo && merged.coverUrl === null && held.upload < 0) {
        held.upload = plan.uploads.length
        plan.uploads.push({ bookId: twin.id, blob: photo })
      }
      index.replace(current, merged)
      target.set(raw.id, twin.id)
      continue
    }

    const added = statusDates({ ...book, dateAdded: book.dateAdded > 0 ? book.dateAdded : now }, book.status, now)
    plan.added.push(added)
    index.add(added)
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
  for (const book of plan.added) store.create(book, { origin: 'import' })
  for (const m of plan.merged) if (Object.keys(m.changes).length > 0) store.edit('books', m.id, m.changes, { origin: 'import' })
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
