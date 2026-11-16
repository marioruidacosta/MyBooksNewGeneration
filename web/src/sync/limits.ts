/**
 * The server's length limits, from the check constraints of `supabase/migrations/2026111000_library_sync.sql`, by
 * server field name. A row with one value over its limit is refused whole, and then every later edit of that book is
 * refused too, so nothing the site writes may go over (the app checks the review the same way: `MAX_REVIEW_LENGTH`).
 */
export const BOOK_LIMITS = {
  isbn: 20,
  title: 300,
  authors: 300,
  cover_url: 500,
  description: 20_000,
  notes: 20_000,
  review: 2000,
  tags: 2000,
  series: 100,
  series_number: 100,
  lent_to: 100,
} as const

export const MAX_QUOTE_TEXT = 5000

/** The limit of a book field, or undefined when the field has none. */
export const limitOf = (field: string): number | undefined => (BOOK_LIMITS as Record<string, number>)[field]

/** Whether [value] is a text longer than its field allows. */
export const isOverLimit = (field: string, value: unknown): boolean => {
  const max = limitOf(field)
  return max !== undefined && typeof value === 'string' && value.length > max
}

/** The first of [fields] that is over its limit, or null. */
export const firstOverLimit = (fields: Record<string, unknown>): string | null =>
  Object.keys(fields).find((f) => isOverLimit(f, fields[f])) ?? null

/** The short message for a text that is too long. */
export const tooLongMessage = (label: string, max: number): string =>
  `${label} is too long (${String(max)} characters at most). It was not saved.`

/** What the person sees when the server refuses a change: never the server's own text. */
export const REFUSED_MESSAGE = 'The server did not accept it. Check that no text is too long, then change it again.'
