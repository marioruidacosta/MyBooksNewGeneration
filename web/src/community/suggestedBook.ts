import type { Found } from '../add/lookup'
import { publicState } from '../share/publisher'
import type { Book } from '../sync/model'
import type { FeedBook } from './model'

/** A community book in the form the library adds. */
export function foundFrom(book: FeedBook): Found {
  return {
    isbn: book.isbn, title: book.title, authors: book.authors.split(',').map((a) => a.trim()).filter(Boolean),
    publisher: null, year: null, pages: book.pages, coverUrl: book.coverUrl, description: book.blurb,
  }
}

/** What a friend gets of a book from the library: only what the community may see (no private cover photo). */
export function suggestedBook(book: Book): FeedBook {
  const shared = publicState(book)
  return {
    shareId: '', isbn: shared.isbn, title: shared.title, authors: shared.authors, coverUrl: shared.cover_url,
    blurb: shared.blurb, pages: shared.pages, currentPage: null,
  }
}
