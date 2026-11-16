import { useState } from 'react'
import type { Book } from '../sync/model'
import { formatDay } from '../text/dates'
import { ProgressEditor } from './ProgressEditor'
import { progressOf, type ProgressInput } from './progress'

type Props = {
  book: Book
  /** null when sharing isn't known (no sharing state to show). */
  shared: boolean | null
  /** The ebook line ("file.epub, EPUB, 120 KB"), when the phone has one. */
  ebook: string | null
  onMarkRead: () => void
  onProgress: (input: ProgressInput) => void
}

/** How far along a reading book is, in words: "Page 132 of 248", or the percentage when the pages aren't known. */
function progressText(book: Book, percent: number): string {
  if (book.currentPage !== null && book.pageCount !== null && book.pageCount > 0) {
    return `Page ${String(book.currentPage)} of ${String(book.pageCount)}`
  }
  return `${String(percent)}% read`
}

/**
 * The summary beside the cover (under the hero on a small screen): progress, dates, tags, who has the book, sharing,
 * the ebook, and the two quick actions.
 */
export function AtAGlance({ book, shared, ebook, onMarkRead, onProgress }: Props) {
  const [editing, setEditing] = useState(false)
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
      {book.tags.length > 0 && (
        <ul className="glance__tags" aria-label="Book tags">
          {book.tags.map((t) => (
            <li key={t} className="tag-chip">
              {t}
            </li>
          ))}
        </ul>
      )}
      {book.lentTo !== null && (
        <p>{book.lentOn !== null ? `Lent to ${book.lentTo} on ${formatDay(book.lentOn)}` : `Lent to ${book.lentTo}`}</p>
      )}
      {shared !== null && <p className="share-state">{shared ? 'Shared with friends' : 'Not shared'}</p>}
      {ebook !== null && <p>Ebook: {ebook}</p>}
      {(book.status !== 'READ' || reading) && (
        <div className="glance__actions">
          {reading &&
            (editing ? (
              <ProgressEditor book={book} onSave={onProgress} onClose={() => setEditing(false)} />
            ) : (
              <button type="button" onClick={() => setEditing(true)}>
                I&apos;m on page…
              </button>
            ))}
          {book.status !== 'READ' && (
            <button type="button" className="primary" onClick={onMarkRead}>
              Mark as read
            </button>
          )}
        </div>
      )}
    </aside>
  )
}
