import { catalogTitleKey, firstAuthorKey } from '../text/keys'
import type { Book } from '../sync/model'

export const missingCover = (books: Book[]): Book[] => books.filter((b) => b.coverUrl === null || b.coverUrl === '')
export const missingPages = (books: Book[]): Book[] => books.filter((b) => b.pageCount === null || b.pageCount <= 0)
export const missingIsbn = (books: Book[]): Book[] => books.filter((b) => b.isbn === null || b.isbn === '')

/**
 * Books that look like the same one: the same title (before any subtitle) by the same first author, or the same ISBN.
 * Only suggestions, in library order; a group is every book linked by either rule.
 */
export function duplicateGroups(books: Book[]): Book[][] {
  const parent = books.map((_, i) => i)
  const find = (i: number): number => (parent[i] === i ? i : (parent[i] = find(parent[i])))
  const link = (a: number, b: number) => {
    parent[find(a)] = find(b)
  }
  const seen = new Map<string, number>()
  books.forEach((b, i) => {
    const keys: string[] = []
    if (b.isbn) keys.push(`isbn:${b.isbn}`)
    const title = catalogTitleKey(b.title)
    const author = firstAuthorKey(b.authors)
    // Without an author a title alone proves nothing: such books group only by ISBN.
    if (title !== '' && author !== '') keys.push(`ta:${title}|${author}`)
    for (const k of keys) {
      const first = seen.get(k)
      if (first === undefined) seen.set(k, i)
      else link(first, i)
    }
  })
  const groups = new Map<number, Book[]>()
  books.forEach((b, i) => {
    const root = find(i)
    const g = groups.get(root)
    if (g) g.push(b)
    else groups.set(root, [b])
  })
  return [...groups.values()].filter((g) => g.length > 1)
}
