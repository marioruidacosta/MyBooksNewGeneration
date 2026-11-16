import type { Found } from '../add/lookup'
import type { Book } from '../sync/model'

const blank = (v: string | null): boolean => v === null || v.trim() === ''

/** What a lookup can add to [book]: only fields the book has empty (never an overwrite), by the server's field names. */
export function fillEmpty(book: Book, found: Found): Record<string, unknown> {
  const out: Record<string, unknown> = {}
  if (blank(book.isbn) && found.isbn) out.isbn = found.isbn
  if (blank(book.authors) && found.authors.length > 0) out.authors = found.authors.join(', ')
  if (blank(book.publisher) && found.publisher) out.publisher = found.publisher
  if (book.publishedYear === null && found.year !== null) out.published_year = found.year
  if ((book.pageCount === null || book.pageCount <= 0) && found.pages !== null) out.page_count = found.pages
  if (blank(book.coverUrl) && found.coverUrl) out.cover_url = found.coverUrl
  if (blank(book.description) && found.description) out.description = found.description
  return out
}
