import type { Book } from '../sync/model'

/** Removes accents: decomposes (NFD) and drops the combining marks ("Ética" gives "Etica"). */
export function foldAccents(text: string): string {
  return text.normalize('NFD').replace(/\p{Mn}+/gu, '')
}

/** Lowercase and without accents ("José" gives "jose"). */
export function searchKey(text: string): string {
  return foldAccents(text).toLowerCase()
}

/** "Ensaio sobre a Cegueira: romance" and "Ensaio Sobre a Cegueira" are the same book. */
export function catalogTitleKey(title: string): string {
  const i = title.indexOf(':')
  return searchKey((i < 0 ? title : title.slice(0, i)).trim())
}

/** Who an author is, whatever the name order: the folded words of the name, sorted. */
export function authorKey(name: string): string {
  return searchKey(name)
    .split(/[^\p{L}\p{N}]+/u)
    .filter((w) => w.length > 0)
    .sort()
    .join(' ')
}

function firstAuthorKey(authors: string): string {
  const i = authors.indexOf(',')
  return authorKey((i < 0 ? authors : authors.slice(0, i)).trim())
}

export type Candidate = { isbn?: string | null; title: string; authors: string }

/** The library's copy of a candidate: same ISBN, or the same title (before any subtitle) by the same first author. */
export function findInLibrary(candidate: Candidate, books: Book[]): Book | undefined {
  const isbn = candidate.isbn ?? null
  const byIsbn = isbn !== null ? books.find((b) => b.isbn === isbn) : undefined
  if (byIsbn) return byIsbn
  const title = catalogTitleKey(candidate.title)
  const author = firstAuthorKey(candidate.authors)
  return books.find((b) => catalogTitleKey(b.title) === title && firstAuthorKey(b.authors) === author)
}
