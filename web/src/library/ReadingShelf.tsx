import { CoverImage } from '../covers/CoverImage'
import type { Book } from '../sync/model'

/** How far into [book] the reader is, 0–100, or null when nothing says. */
function progressOf(book: Book): number | null {
  if (book.progressPercent !== null) return Math.max(0, Math.min(100, book.progressPercent))
  if (book.currentPage !== null && book.pageCount !== null && book.pageCount > 0) {
    return Math.max(0, Math.min(100, Math.round((book.currentPage / book.pageCount) * 100)))
  }
  return null
}

/** The books being read, most recently started first, as covers on a shelf with how far along each one is. */
export function ReadingShelf({ books, onOpen }: { books: Book[]; onOpen: (id: string) => void }) {
  const reading = books
    .filter((b) => b.status === 'READING')
    .sort((a, b) => (b.dateStarted ?? 0) - (a.dateStarted ?? 0))
  if (reading.length === 0) return null
  const base = import.meta.env.BASE_URL
  return (
    <section className="shelf" aria-labelledby="shelf-title">
      <h2 id="shelf-title">Reading now</h2>
      <ul className="shelf-row">
        {reading.map((b) => {
          const progress = progressOf(b)
          return (
            <li key={b.id} className="shelf-book">
              <a
                href={`${base}book/${b.id}`}
                aria-label={b.title}
                onClick={(e) => {
                  if (e.button !== 0 || e.metaKey || e.ctrlKey || e.shiftKey || e.altKey) return
                  e.preventDefault()
                  onOpen(b.id)
                }}
              >
                <CoverImage book={b} className="shelf-cover" />
                <span className="shelf-title">{b.title}</span>
              </a>
              {progress !== null && (
                <span
                  className="shelf-progress"
                  role="progressbar"
                  aria-label={`${b.title} progress`}
                  aria-valuemin={0}
                  aria-valuemax={100}
                  aria-valuenow={progress}
                >
                  <span style={{ width: `${progress}%` }} />
                </span>
              )}
              <span className="shelf-meta">{progress !== null ? `${progress}%` : 'Just started'}</span>
            </li>
          )
        })}
      </ul>
    </section>
  )
}
