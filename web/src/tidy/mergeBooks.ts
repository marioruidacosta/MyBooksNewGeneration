import { BUCKET, forgetSignedUrl, photoPath, type CoverStorage } from '../covers/storage'
import { bookToFields, rowToBook } from '../sync/codec'
import type { Book, SyncRow } from '../sync/model'
import { SHARE_EXCLUDED_PREFIX } from '../share/publisher'
import type { LibraryStore } from '../sync/store'
import { bookcasesOf, SHELF_FIELDS } from '../physical/places'
import { shelfIds } from '../physical/model'
import { combineRows, withShelfPair } from '../sync/twin'
import { statusDates } from '../book/status'

/** Supabase Storage as merging needs it: the cover bucket's copy (the real `supabase.storage` fits). */
export type PhotoStorage = {
  from(bucket: string): ReturnType<CoverStorage['from']> & {
    copy(from: string, to: string): PromiseLike<{ error: { message: string } | null }>
    download(path: string): PromiseLike<{ data: Blob | null; error: { message: string } | null }>
  }
}

const LABELS: Record<string, string> = {
  isbn: 'ISBN', title: 'title', authors: 'authors', publisher: 'publisher', published_year: 'year',
  page_count: 'pages', cover_url: 'cover', description: 'description', status: 'status', rating: 'rating',
  notes: 'notes', date_added: 'date added', date_read: 'date read', date_started: 'date started',
  current_page: 'current page', progress_percent: 'progress', review: 'review', tags: 'tags', series: 'series',
  series_number: 'series number', lent_to: 'lent to', lent_on: 'lent on', shelf_id: 'shelf',
  shelf_position: 'place on the shelf',
}

export type MergePlan = {
  /** The combined book, as the twin rule makes it (a photo cover not yet checked). */
  merged: Book
  /** The server names of the fields of the kept book that change. */
  fields: string[]
  /** The same fields, named for the person. */
  changes: string[]
  /** How many quotes move to the kept book. */
  quotes: number
  /** The merged cover is the dropped book's photo, which has to be copied to the kept book's own file. */
  photoFromDrop: boolean
  /** ...and [keep] has a photo of its own, which that replaces (so the file is downloaded and uploaded over it). */
  replacesPhoto: boolean
  /** [drop] has bookmarks or an e-book record, which go with it. */
  phoneData: boolean
}

const rowOf = (store: LibraryStore, b: Book): SyncRow => ({
  table: 'books', id: b.id, fields: bookToFields(b), times: store.bookTimes(b.id),
})

/** What merging [drop] into [keep] would do, without doing it. Uses the one twin rule, `combineRows`. */
export function planMerge(store: LibraryStore, keep: Book, drop: Book, now = Date.now()): MergePlan {
  const keepRow = rowOf(store, keep)
  const dropRow = rowOf(store, drop)
  // The dates a status goes with, as on import: a To-read book keeps no read date, a Read one has its date.
  const shelves = new Set(bookcasesOf(store).flatMap(shelfIds))
  const twins = withShelfPair(combineRows(keepRow, dropRow), keepRow, dropRow, (id) => shelves.has(id))
  const merged = statusDates(rowToBook(twins), keep.status, now)
  const before = bookToFields(keep)
  const after = bookToFields(merged)
  const fields = Object.keys(after).filter((f) => f !== 'deleted' && after[f] !== before[f])
  const photoFromDrop =
    merged.coverUrl === 'photo' &&
    drop.coverUrl === 'photo' &&
    (keep.coverUrl !== 'photo' || (dropRow.times.cover_url ?? -1) > (keepRow.times.cover_url ?? -1))
  return {
    merged,
    fields,
    changes: fields.map((f) => LABELS[f] ?? f),
    quotes: store.quotes(drop.id).length,
    photoFromDrop,
    replacesPhoto: photoFromDrop && keep.coverUrl === 'photo',
    phoneData: store.hasPhoneData(drop.id),
  }
}

/**
 * Merges [drop] into [keep]: the twin rule's result goes to [keep], [drop]'s quotes move to it (their `book_id` is
 * edited, so it syncs), then [drop] is removed. A photo cover that wins from [drop] is first copied to [keep]'s own
 * file (the path is tied to the book's id); if that fails [keep] stays as it was for the cover, and `coverKept` is
 * true. No storage object is ever deleted.
 *
 * `undo` puts it back, writing as little as it can so that nothing changed since is lost (the phone may have edited
 * within the seconds Undo is on offer): of the fields the merge wrote to [keep], only those whose value is still the
 * one the merge wrote go back to what they were; [drop] gets `deleted: false` and nothing else; its quotes that are
 * still on [keep] move back. A cover photo copied to [keep]'s file can't be un-copied (storage is never touched):
 * Undo only puts back [keep]'s previous `cover_url` value, so when [keep] already had a photo its original image is
 * not restored (the new one stays in its file). Bookmarks and an e-book record of [drop] on a phone were removed with
 * it and are not brought back.
 */
export async function mergeBooks(
  store: LibraryStore,
  keep: Book,
  drop: Book,
  photos?: { owner: string; storage: PhotoStorage },
): Promise<{ coverKept: boolean; undo: () => void }> {
  const plan = planMerge(store, keep, drop)
  const keepBefore = keep
  const dropBefore = drop
  const dropTimes = store.bookTimes(drop.id)
  const changes: Record<string, unknown> = {}
  const after = bookToFields(plan.merged)
  for (const f of plan.fields) changes[f] = after[f]

  let coverKept = false
  const restamp: string[] = []
  // The place's two fields are timed together, even when only one of them differs.
  if (SHELF_FIELDS.some((f) => f in changes)) {
    for (const f of SHELF_FIELDS) {
      changes[f] = after[f]
      restamp.push(f)
    }
  }
  if (plan.photoFromDrop) {
    let copied = false
    if (photos) {
      const to = photoPath(photos.owner, keep.id)
      const from = photoPath(photos.owner, drop.id)
      const bucket = photos.storage.from(BUCKET)
      try {
        if (plan.replacesPhoto) {
          // A copy won't overwrite: take the other photo's bytes and upload them over the kept file. The kept file is
          // never deleted first, so a failure leaves it as it was.
          const { data, error } = await bucket.download(from)
          if (error || !data) throw new Error(error?.message ?? 'no photo')
          const up = await bucket.upload(to, data, { upsert: true, contentType: 'image/jpeg' })
          copied = !up.error
        } else {
          copied = !(await bucket.copy(from, to)).error
        }
        if (copied) forgetSignedUrl(photos.storage, to)
      } catch {
        copied = false
      }
    }
    if (copied) {
      // Always set, so the cover's time moves even when the marker was already 'photo' and phones fetch the new file.
      changes.cover_url = 'photo'
      restamp.push('cover_url')
    } else {
      coverKept = true
      delete changes.cover_url
    }
  }

  const quotes = store.quotes(drop.id)
  // "Don't share" on either copy wins: the kept book is about to take on what the private one holds (a score, a review).
  // Set first, so that nothing of it can be posted.
  const keptKey = SHARE_EXCLUDED_PREFIX + keep.id
  const inherited = store.pref(SHARE_EXCLUDED_PREFIX + drop.id) === '1' && store.pref(keptKey) !== '1'
  if (inherited) store.setPref(keptKey, '1')
  if (Object.keys(changes).length > 0 || restamp.length > 0) {
    store.edit('books', keep.id, changes, { restamp, origin: 'bulk' })
  }
  for (const q of quotes) store.edit('quotes', q.id, { book_id: keep.id })
  store.remove('books', drop.id)
  const wrote = { ...changes }
  const undo = () => {
    const now = store.book(keep.id)
    if (now) {
      const current = bookToFields(now)
      const was = bookToFields(keepBefore)
      const back: Record<string, unknown> = {}
      for (const [f, v] of Object.entries(wrote)) if (!SHELF_FIELDS.includes(f) && current[f] === v) back[f] = was[f]
      // The place goes back whole, and only if neither of its fields changed since.
      const place = SHELF_FIELDS.every((f) => f in wrote && current[f] === wrote[f]) ? SHELF_FIELDS : []
      for (const f of place) back[f] = was[f]
      if (Object.keys(back).length > 0) store.edit('books', keep.id, back, { origin: 'bulk', restamp: place })
    }
    store.restoreBook(dropBefore, dropTimes)
    const onKeep = new Set(store.quotes(keep.id).map((q) => q.id))
    for (const q of quotes) if (onKeep.has(q.id)) store.edit('quotes', q.id, { book_id: dropBefore.id })
    // Last, once the kept book no longer holds the private copy's words.
    if (inherited) store.setPref(keptKey, null)
  }
  return { coverKept, undo }
}
