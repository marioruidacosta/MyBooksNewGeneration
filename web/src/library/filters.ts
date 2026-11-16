import { searchKey } from '../text/keys'
import { STATUSES, type Book, type Status } from '../sync/model'

/** The app's words for the statuses. */
export const STATUS_LABELS: Record<Status, string> = {
  READING: 'Reading',
  TO_READ: 'To read',
  READ: 'Read',
  WISHLIST: 'To buy',
}

export type LentFilter = '' | 'out' | 'in'

/** What narrows the table; an empty value leaves that part open. */
export type Filters = { query: string; status: Status | ''; tag: string; lent: LentFilter }

export const NO_FILTERS: Filters = { query: '', status: '', tag: '', lent: '' }

/** Every word of the search is in the title, the authors or the ISBN (case and accents aside). */
export function matchesQuery(book: Book, query: string): boolean {
  const words = searchKey(query).split(/\s+/).filter((w) => w.length > 0)
  if (words.length === 0) return true
  const haystack = searchKey(`${book.title}\n${book.authors}\n${book.isbn ?? ''}`)
  return words.every((w) => haystack.includes(w))
}

export function matches(book: Book, f: Filters): boolean {
  if (f.status !== '' && book.status !== f.status) return false
  if (f.tag !== '' && !book.tags.includes(f.tag)) return false
  if (f.lent === 'out' && book.lentTo === null) return false
  if (f.lent === 'in' && book.lentTo !== null) return false
  return matchesQuery(book, f.query)
}

/** Every tag in use, in alphabetical order. */
export function allTags(books: Book[]): string[] {
  const tags = new Set<string>()
  for (const b of books) b.tags.forEach((t) => tags.add(t))
  return [...tags].sort((a, b) => searchKey(a).localeCompare(searchKey(b)) || a.localeCompare(b))
}

export function countByStatus(books: Book[]): Record<Status, number> {
  const counts = Object.fromEntries(STATUSES.map((s) => [s, 0])) as Record<Status, number>
  for (const b of books) counts[b.status]++
  return counts
}

export const plural = (n: number, one: string, many: string = `${one}s`) => `${n} ${n === 1 ? one : many}`
