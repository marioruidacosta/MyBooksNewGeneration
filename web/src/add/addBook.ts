import { authorKey, findInLibrary } from '../text/keys'
import type { Book } from '../sync/model'
import type { LibraryStore } from '../sync/store'
import type { Found } from './lookup'

export type Added = { id: string; added: boolean }

/** The library's spelling of each author, by `authorKey`. */
function libraryAuthors(books: Book[]): Map<string, string> {
  const spelling = new Map<string, string>()
  for (const b of books) {
    for (const name of b.authors.split(',').map((n) => n.trim())) {
      if (name && !spelling.has(authorKey(name))) spelling.set(authorKey(name), name)
    }
  }
  return spelling
}

/** Authors as one text, in the library's spelling for authors it already has. */
export function authorsText(found: Found, books: Book[]): string {
  const spelling = libraryAuthors(books)
  return found.authors.map((a) => spelling.get(authorKey(a)) ?? a).join(', ')
}

/** The library's copy of a found book, if it has one. */
export function inLibrary(found: Found, books: Book[]): Book | undefined {
  return findInLibrary({ isbn: found.isbn, title: found.title, authors: authorsText(found, books) }, books)
}

/** Adds the book as To read, unless the library has it: then `added` is false and `id` is the existing book. */
export function addBook(store: LibraryStore, found: Found, now = Date.now()): Added {
  const books = store.books()
  const existing = inLibrary(found, books)
  if (existing) return { id: existing.id, added: false }
  const id = crypto.randomUUID()
  store.create({
    id, isbn: found.isbn, title: found.title, authors: authorsText(found, books), publisher: found.publisher,
    publishedYear: found.year, pageCount: found.pages, coverUrl: found.coverUrl, description: found.description,
    status: 'TO_READ', rating: null, notes: '', dateAdded: now, dateRead: null, dateStarted: null, currentPage: null,
    progressPercent: null, review: '', tags: [], series: null, seriesNumber: null, lentTo: null, lentOn: null,
    deleted: false,
  })
  return { id, added: true }
}
