import { shrinkToJpeg, uploadCover, MAX_BYTES, MAX_SIDE, type ImageDecoder } from '../covers/coverUpload'
import type { CoverStorage } from '../covers/storage'
import { bookToFields, rowToBook } from '../sync/codec'
import { statusDates } from '../book/status'
import type { Book, Quote, SyncRow } from '../sync/model'
import type { LibraryStore } from '../sync/store'
import { BOOK_LIMITS } from '../sync/limits'
import { BOOKCASE_PREFIX, parseBookcase } from '../physical/model'
import { SHELF_FIELDS } from '../physical/places'
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
  /** The physical library's bookcases, in a backup. */
  bookcases?: { id: string; value: string }[]
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
    quotes: backup.quotes, challenges: backup.challenges, bookcases: backup.bookcases, covers: backup.covers, keepRead: false,
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
  /** Bookcases of the file: each replaces the one with its id. */
  bookcases?: { id: string; value: string }[]
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
  // Over the server's limits a row is refused whole: long texts are cut (the review is refused above), a cover link
  // that is too long is left out, and the tags stop where the limit is reached.
  const tags: string[] = []
  let tagsLength = 0
  for (const t of cleanTags(b.tags)) {
    const next = tagsLength + (tags.length > 0 ? 1 : 0) + t.length
    if (next > BOOK_LIMITS.tags) break
    tagsLength = next
    tags.push(t)
  }
  return {
    ...b,
    title: title.slice(0, BOOK_LIMITS.title).trim(),
    authors: b.authors.slice(0, BOOK_LIMITS.authors).trim(),
    coverUrl: b.coverUrl !== null && b.coverUrl.length > BOOK_LIMITS.cover_url ? null : b.coverUrl,
    description: b.description === null ? null : b.description.slice(0, BOOK_LIMITS.description),
    notes: b.notes.slice(0, BOOK_LIMITS.notes),
    isbn,
    currentPage: b.currentPage !== null && b.currentPage >= 0 ? b.currentPage : null,
    progressPercent: b.progressPercent === null ? null : Math.min(100, Math.max(0, b.progressPercent)),
    tags,
    series,
    seriesNumber: series !== null ? number : null,
    lentTo,
    lentOn: lentTo !== null ? b.lentOn : null,
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
  /** Queued photos given up because a later row of the file set a web cover. */
  const cancelled = new Set<number>()
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
      const twins = combineRows(keepRow, rowOf(incoming, times))
      // A backup that says where the book is puts it there; one that doesn't leaves it where it is.
      const combined = incoming.shelfId === null
        ? twins
        : { ...twins, fields: { ...twins.fields, shelf_id: incoming.shelfId, shelf_position: incoming.shelfPosition } }
      const merged = statusDates(rowToBook(combined), incoming.status, now)
      const row = rowOf(merged, combined.times)
      const before = bookToFields(original)
      const after = bookToFields(merged)
      const changes: Record<string, unknown> = {}
      for (const f of Object.keys(after)) {
        if (f !== 'deleted' && after[f] !== before[f]) changes[f] = after[f]
      }
      // The place's two fields are written and timed together, even when only one of them differs.
      if (SHELF_FIELDS.some((f) => f in changes)) for (const f of SHELF_FIELDS) changes[f] = after[f]
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
      } else if (held.upload >= 0 && merged.coverUrl !== null) {
        // A later row gave the book a web cover: the queued photo must not replace it.
        cancelled.add(held.upload)
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

  plan.bookcases = (source.bookcases ?? []).filter((b) => parseBookcase(BOOKCASE_PREFIX + b.id, b.value) !== null)

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
  if (cancelled.size > 0) plan.uploads = plan.uploads.filter((_, i) => !cancelled.has(i))

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
  /** Called as each photo starts uploading: its place (from 1) among `total`. */
  onPhoto?: (n: number, total: number) => void
}

/**
 * Writes the plan. The store queues every change and sends them [PUSH_BATCH] at a time (the same pass as any edit), so
 * nothing here batches. Photos go up last, one by one; one that fails is counted and its book stays as imported.
 */
export async function applyPlan(plan: Plan, store: LibraryStore, deps: ApplyDeps): Promise<{ coverFailures: number }> {
  for (const book of plan.added) store.create(book, { origin: 'import' })
  for (const m of plan.merged) {
    if (Object.keys(m.changes).length === 0) continue
    const restamp = SHELF_FIELDS.some((f) => f in m.changes) ? SHELF_FIELDS : []
    store.edit('books', m.id, m.changes, { origin: 'import', restamp })
  }
  for (const q of plan.quotes) store.createQuote(q)
  for (const g of plan.goals) store.edit('challenges', String(g.year), { goal: g.goal })
  for (const b of plan.bookcases ?? []) store.setPref(BOOKCASE_PREFIX + b.id, b.value)

  let coverFailures = 0
  for (const [i, { bookId, blob }] of plan.uploads.entries()) {
    deps.onPhoto?.(i + 1, plan.uploads.length)
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
