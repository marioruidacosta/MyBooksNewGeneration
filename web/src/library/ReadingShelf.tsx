import { useState } from 'react'
import { progressOf, type ProgressInput } from '../book/progress'
import { ProgressEditor } from '../book/ProgressEditor'
import { CoverImage } from '../covers/CoverImage'
import type { Book } from '../sync/model'

/**
 * The books being read, most recently started first, as covers on a shelf with how far along each one is. Clicking the
 * bar or the text under it opens a small editor ([onProgress] saves what it gives).
 */
export function ReadingShelf({
  books, onOpen, onProgress,
}: {
  books: Book[]
  onOpen: (id: string) => void
  onProgress: (book: Book, input: ProgressInput) => void
}) {
  const [editing, setEditing] = useState<string | null>(null)
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
                // The bar is a convenience for the mouse; the button under it is the same thing for everyone.
                <span
                  className="shelf-progress shelf-progress--edit"
                  role="progressbar"
                  aria-label={`${b.title} progress`}
                  aria-valuemin={0}
                  aria-valuemax={100}
                  aria-valuenow={progress}
                  onClick={() => setEditing(b.id)}
                >
                  <span style={{ width: `${progress}%` }} />
                </span>
              )}
              {editing === b.id ? (
                <ProgressEditor
                  book={b}
                  onSave={(input) => onProgress(b, input)}
                  onClose={() => setEditing(null)}
                />
              ) : (
                <button
                  type="button"
                  className="shelf-meta"
                  aria-label={`Update progress for ${b.title}`}
                  onClick={() => setEditing(b.id)}
                >
                  {progress !== null ? `${progress}%` : 'Just started'}
                </button>
              )}
            </li>
          )
        })}
      </ul>
    </section>
  )
}
