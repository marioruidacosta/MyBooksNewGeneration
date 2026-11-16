export type SyncTable = 'books' | 'quotes' | 'challenges' | 'ebooks' | 'bookmarks' | 'prefs'

/**
 * One row as it travels. `fields` by the server's column name, `times` the last-change time (ms) of each field.
 * `deleted` is an ordinary field (a JSON boolean) with its own time.
 */
export type SyncRow = {
  table: SyncTable
  id: string
  fields: Record<string, unknown>
  times: Record<string, number>
  seq?: number
}

export type Status = 'READING' | 'TO_READ' | 'READ' | 'WISHLIST'

export const STATUSES: readonly Status[] = ['READING', 'TO_READ', 'READ', 'WISHLIST']

/** The book tags travel as one text, joined with this. */
export const TAG_SEPARATOR = '\n'

export type Book = {
  id: string
  isbn: string | null
  title: string
  authors: string
  publisher: string | null
  publishedYear: number | null
  pageCount: number | null
  coverUrl: string | null
  description: string | null
  status: Status
  rating: number | null
  notes: string
  dateAdded: number
  dateRead: number | null
  dateStarted: number | null
  currentPage: number | null
  progressPercent: number | null
  review: string
  tags: string[]
  series: string | null
  seriesNumber: string | null
  lentTo: string | null
  lentOn: number | null
  deleted: boolean
}

export type Quote = {
  id: string
  bookId: string
  text: string
  page: number | null
  dateAdded: number
  position: string | null
  deleted: boolean
}

/** The year is the id. */
export type Challenge = {
  year: number
  goal: number
  deleted: boolean
}

/** A row of `sync_pull`: the server returns `deleted` beside `fields`; here it becomes an ordinary field, as in the app. */
export function wireToRow(o: {
  table: SyncTable
  id: string
  fields?: Record<string, unknown> | null
  times?: Record<string, number> | null
  deleted?: boolean | null
  seq?: number | null
}): SyncRow {
  const fields = { ...(o.fields ?? {}) }
  if (o.deleted !== null && o.deleted !== undefined) fields.deleted = o.deleted
  return { table: o.table, id: o.id, fields, times: { ...(o.times ?? {}) }, seq: o.seq ?? 0 }
}
