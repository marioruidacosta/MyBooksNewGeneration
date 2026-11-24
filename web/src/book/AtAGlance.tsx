import type { ReactNode } from 'react'
import type { Book } from '../sync/model'
import { formatDay } from '../text/dates'
import { progressOf } from './progress'

type Props = {
  book: Book
  /** "Suggest to a friend", after the dates. */
  suggest?: ReactNode
  /** Where the book is on the real shelves, right after suggesting it. */
  whereItIs?: ReactNode
  /** The ebook line ("file.epub, EPUB, 120 KB"), when the phone has one. */
  ebook: string | null
}

/** How far along a reading book is, in words: "Page 132 of 248", or the percentage when the pages aren't known. */
function progressText(book: Book, percent: number): string {
  if (book.currentPage !== null && book.pageCount !== null && book.pageCount > 0) {
    return `Page ${String(book.currentPage)} of ${String(book.pageCount)}`
  }
  return `${String(percent)}% read`
}

/**
 * The summary beside the cover (under the hero on a small screen): progress, dates, suggesting it to a friend, where it
 * is at home, who has the book and the ebook.
 */
export function AtAGlance({ book, suggest, whereItIs, ebook }: Props) {
  const reading = book.status === 'READING'
  const progress = reading ? progressOf(book) : null
  return (
    <aside className="glance" aria-labelledby="glance-title">
      <h2 id="glance-title">At a glance</h2>
      {reading && progress !== null && (
        <div className="glance__progress">
          <span
            className="shelf-progress"
            role="progressbar"
            aria-label={`${book.title} progress`}
            aria-valuemin={0}
            aria-valuemax={100}
            aria-valuenow={progress}
          >
            <span style={{ width: `${String(progress)}%` }} />
          </span>
          <span>{progressText(book, progress)}</span>
        </div>
      )}
      <dl className="glance__facts">
        {book.dateStarted !== null && (
          <div>
            <dt>Started</dt>
            <dd>{formatDay(book.dateStarted)}</dd>
          </div>
        )}
        {book.dateRead !== null && (
          <div>
            <dt>Finished</dt>
            <dd>{formatDay(book.dateRead)}</dd>
          </div>
        )}
      </dl>
      {suggest}
      {whereItIs}
      {book.lentTo !== null && (
        <p>{book.lentOn !== null ? `Lent to ${book.lentTo} on ${formatDay(book.lentOn)}` : `Lent to ${book.lentTo}`}</p>
      )}
      {ebook !== null && <p>Ebook: {ebook}</p>}
    </aside>
  )
}
