import type { Book } from '../sync/model'

/** What the person typed to say how far along they are: a page, or a percentage when the page count isn't known. */
export type ProgressInput = { page: number } | { percent: number }

/**
 * Reads typed progress as the app's `parseProgress`: whole numbers only, a page from 0 up, a percentage from 0 to 100.
 * Null when it isn't one of those.
 */
export function parseProgress(text: string, byPages: boolean): number | null {
  const t = text.trim()
  if (!/^\d+$/.test(t)) return null
  const n = Number(t)
  return byPages || n <= 100 ? n : null
}

/**
 * The fields quick progress sets, as the app's `setProgressPages` / `setProgressPercent`: a page also sets the
 * percentage that follows from the page count (rounded down, at most 100; none when the count is unknown), and a
 * percentage forgets the page. The status is never changed here, as in the app (it only asks "Finished?" at 100).
 */
export function progressChange(book: Pick<Book, 'pageCount'>, input: ProgressInput): Record<string, unknown> {
  if ('page' in input) {
    const count = book.pageCount !== null && book.pageCount > 0 ? book.pageCount : null
    return {
      current_page: input.page,
      progress_percent: count === null ? null : Math.min(100, Math.floor((input.page * 100) / count)),
    }
  }
  return { current_page: null, progress_percent: input.percent }
}
