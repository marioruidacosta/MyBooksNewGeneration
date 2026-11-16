import { BUCKET, forgetSignedUrl, photoPath, type CoverStorage } from '../covers/storage'
import { bookToFields, rowToBook } from '../sync/codec'
import type { Book, SyncRow } from '../sync/model'
import type { LibraryStore } from '../sync/store'
import { combineRows } from '../sync/twin'

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
  series_number: 'series number', lent_to: 'lent to', lent_on: 'lent on',
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
export function planMerge(store: LibraryStore, keep: Book, drop: Book): MergePlan {
  const keepRow = rowOf(store, keep)
  const dropRow = rowOf(store, drop)
  const merged = rowToBook(combineRows(keepRow, dropRow))
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
 */
export async function mergeBooks(
  store: LibraryStore,
  keep: Book,
  drop: Book,
  photos?: { owner: string; storage: PhotoStorage },
): Promise<{ coverKept: boolean }> {
  const plan = planMerge(store, keep, drop)
  const changes: Record<string, unknown> = {}
  const after = bookToFields(plan.merged)
  for (const f of plan.fields) changes[f] = after[f]

  let coverKept = false
  const restamp: string[] = []
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
  if (Object.keys(changes).length > 0 || restamp.length > 0) {
    store.edit('books', keep.id, changes, { restamp })
  }
  for (const q of quotes) store.edit('quotes', q.id, { book_id: keep.id })
  store.remove('books', drop.id)
  return { coverKept }
}
