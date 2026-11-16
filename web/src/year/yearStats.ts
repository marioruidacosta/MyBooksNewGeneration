import { authorKey } from '../text/keys'
import type { Book } from '../sync/model'

export const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'] as const

export type YearStats = {
  /** The books finished in the year, in the order they were read. */
  read: Book[]
  /** Books finished in each month, January first. */
  perMonth: number[]
  pages: number
  /** How many of the books have no page count (they add nothing to `pages`). */
  withoutPages: number
  /** The mean score of the rated ones, or null when none is rated. */
  averageScore: number | null
  topAuthors: { name: string; count: number }[]
}

/** What a year of reading came to: books with status Read and a finish day (local time) in [year]. */
export function yearStats(books: Book[], year: number, topCount = 5): YearStats {
  const read = books
    .filter((b) => !b.deleted && b.status === 'READ' && b.dateRead !== null && new Date(b.dateRead).getFullYear() === year)
    .sort((a, b) => (a.dateRead ?? 0) - (b.dateRead ?? 0))
  const perMonth = Array.from({ length: 12 }, () => 0)
  let pages = 0
  let withoutPages = 0
  const scores: number[] = []
  const authors = new Map<string, { name: string; count: number }>()
  for (const b of read) {
    perMonth[new Date(b.dateRead ?? 0).getMonth()]++
    if (b.pageCount !== null && b.pageCount > 0) pages += b.pageCount
    else withoutPages++
    if (b.rating !== null) scores.push(b.rating)
    for (const name of b.authors.split(',').map((n) => n.trim()).filter((n) => n !== '')) {
      const key = authorKey(name)
      const entry = authors.get(key)
      if (entry) entry.count++
      else authors.set(key, { name, count: 1 })
    }
  }
  const topAuthors = [...authors.values()]
    .sort((a, b) => b.count - a.count || a.name.localeCompare(b.name))
    .slice(0, topCount)
  return {
    read,
    perMonth,
    pages,
    withoutPages,
    averageScore: scores.length === 0 ? null : scores.reduce((a, b) => a + b, 0) / scores.length,
    topAuthors,
  }
}
