import {
  STATUSES, TAG_SEPARATOR, type Book, type Challenge, type Quote, type Status, type SyncRow,
} from './model'

// Reads like the app's SyncCodec: a missing or mistyped value is null (or the default), never an error.

type Fields = Record<string, unknown>

const str = (f: Fields, k: string): string | null => (typeof f[k] === 'string' ? (f[k] as string) : null)
const num = (f: Fields, k: string): number | null =>
  typeof f[k] === 'number' && Number.isFinite(f[k]) ? Math.trunc(f[k] as number) : null
const float = (f: Fields, k: string): number | null =>
  typeof f[k] === 'number' && Number.isFinite(f[k]) ? (f[k] as number) : null
const isDeleted = (f: Fields): boolean => f.deleted === true

function splitTags(text: string): string[] {
  return text.split(TAG_SEPARATOR).filter((t) => t.length > 0)
}

export function rowToBook(row: SyncRow): Book {
  const f = row.fields
  const status = str(f, 'status')
  return {
    id: row.id,
    isbn: str(f, 'isbn'),
    title: str(f, 'title') ?? '',
    authors: str(f, 'authors') ?? '',
    publisher: str(f, 'publisher'),
    publishedYear: num(f, 'published_year'),
    pageCount: num(f, 'page_count'),
    coverUrl: str(f, 'cover_url'),
    description: str(f, 'description'),
    status: STATUSES.find((s) => s === status) ?? 'TO_READ',
    rating: num(f, 'rating'),
    notes: str(f, 'notes') ?? '',
    dateAdded: num(f, 'date_added') ?? 0,
    dateRead: num(f, 'date_read'),
    dateStarted: num(f, 'date_started'),
    currentPage: num(f, 'current_page'),
    progressPercent: num(f, 'progress_percent'),
    review: str(f, 'review') ?? '',
    tags: splitTags(str(f, 'tags') ?? ''),
    series: str(f, 'series'),
    seriesNumber: str(f, 'series_number'),
    lentTo: str(f, 'lent_to'),
    lentOn: num(f, 'lent_on'),
    shelfId: str(f, 'shelf_id'),
    shelfPosition: float(f, 'shelf_position'),
    deleted: isDeleted(f),
  }
}

export function bookToFields(b: Book): Fields {
  const status: Status = b.status
  return {
    isbn: b.isbn, title: b.title, authors: b.authors, publisher: b.publisher,
    published_year: b.publishedYear, page_count: b.pageCount, cover_url: b.coverUrl,
    description: b.description, status, rating: b.rating, notes: b.notes,
    date_added: b.dateAdded, date_read: b.dateRead, date_started: b.dateStarted,
    current_page: b.currentPage, progress_percent: b.progressPercent, review: b.review,
    tags: b.tags.join(TAG_SEPARATOR), series: b.series, series_number: b.seriesNumber,
    lent_to: b.lentTo, lent_on: b.lentOn, shelf_id: b.shelfId, shelf_position: b.shelfPosition, deleted: b.deleted,
  }
}

export function rowToQuote(row: SyncRow): Quote {
  const f = row.fields
  return {
    id: row.id,
    bookId: str(f, 'book_id') ?? '',
    text: str(f, 'text') ?? '',
    page: num(f, 'page'),
    dateAdded: num(f, 'date_added') ?? 0,
    position: str(f, 'position'),
    deleted: isDeleted(f),
  }
}

export function quoteToFields(q: Quote): Fields {
  return {
    book_id: q.bookId, text: q.text, page: q.page, date_added: q.dateAdded, position: q.position,
    deleted: q.deleted,
  }
}

/** The row id is the year; a row whose id is not a year gives NaN. */
export function rowToChallenge(row: SyncRow): Challenge {
  return { year: Number.parseInt(row.id, 10), goal: num(row.fields, 'goal') ?? 0, deleted: isDeleted(row.fields) }
}

export function challengeToFields(c: Challenge): Fields {
  return { goal: c.goal, deleted: c.deleted }
}
