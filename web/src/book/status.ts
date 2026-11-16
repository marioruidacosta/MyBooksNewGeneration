import type { Book, Status } from '../sync/model'

/**
 * The fields a status change sets, as the app's `withStatusDates`: Reading starts now unless it already has a start,
 * Read finishes now unless it already has a finish (a start after the finish is dropped), and To read / To buy forget
 * the dates and the progress. Only what differs from the book is returned, so nothing unchanged is stamped.
 */
export function statusChange(book: Book, status: Status, now: number): Record<string, unknown> {
  const before = book.status
  let dateRead: number | null = book.dateRead
  let dateStarted: number | null = book.dateStarted
  let currentPage = book.currentPage
  let progressPercent = book.progressPercent
  if (status === 'READING') {
    dateRead = null
    dateStarted = book.dateStarted ?? (before !== 'READING' ? now : null)
  } else if (status === 'READ') {
    dateRead = book.dateRead ?? (before !== 'READ' ? now : null)
    dateStarted = book.dateStarted !== null && (dateRead === null || book.dateStarted <= dateRead) ? book.dateStarted : null
  } else {
    dateRead = null
    dateStarted = null
    currentPage = null
    progressPercent = null
  }
  const next: Record<string, unknown> = {
    status, date_read: dateRead, date_started: dateStarted, current_page: currentPage, progress_percent: progressPercent,
  }
  const was: Record<string, unknown> = {
    status: before, date_read: book.dateRead, date_started: book.dateStarted, current_page: book.currentPage,
    progress_percent: book.progressPercent,
  }
  return Object.fromEntries(Object.entries(next).filter(([k, v]) => v !== was[k]))
}

/** The dates a status goes with (the app's `withStatusDates`); [previous] is the status the book had. */
export function statusDates(b: Book, previous: Status | null, now: number): Book {
  switch (b.status) {
    case 'READING':
      return { ...b, dateRead: null, dateStarted: b.dateStarted ?? (previous !== 'READING' ? now : null) }
    case 'READ': {
      const read = b.dateRead ?? (previous !== 'READ' ? now : null)
      return { ...b, dateRead: read, dateStarted: b.dateStarted !== null && (read === null || b.dateStarted <= read) ? b.dateStarted : null }
    }
    default:
      return { ...b, dateRead: null, dateStarted: null, currentPage: null, progressPercent: null }
  }
}
