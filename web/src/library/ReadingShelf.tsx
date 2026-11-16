import { useEffect, useRef, useState } from 'react'
import { parseProgress, type ProgressInput } from '../book/progress'
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

/**
 * "Page [ ] of N" (a percentage when the page count is unknown) for one book. Enter or leaving the field saves, Escape
 * cancels; a value that isn't a page or a percentage is not saved.
 */
function ProgressEditor({ book, onSave, onClose }: { book: Book; onSave: (input: ProgressInput) => void; onClose: () => void }) {
  const byPages = book.pageCount !== null && book.pageCount > 0
  const start = byPages ? book.currentPage : book.progressPercent
  const [text, setText] = useState(start === null ? '' : String(start))
  const [invalid, setInvalid] = useState(false)
  const input = useRef<HTMLInputElement>(null)
  // Set once the editor has been settled (saved or cancelled), so closing it does not settle it again on blur.
  const settled = useRef(false)

  useEffect(() => {
    input.current?.focus()
    input.current?.select()
  }, [])

  const finish = (save: boolean) => {
    if (settled.current) return
    if (save && text.trim() !== '') {
      const n = parseProgress(text, byPages)
      if (n === null) {
        setInvalid(true)
        return
      }
      onSave(byPages ? { page: n } : { percent: n })
    }
    settled.current = true
    onClose()
  }

  return (
    <span className="shelf-edit">
      {byPages && <span aria-hidden="true">Page</span>}
      <input
        ref={input}
        type="number"
        min={0}
        max={byPages ? undefined : 100}
        inputMode="numeric"
        aria-label={byPages ? `Current page of ${book.title}` : `Percent read of ${book.title}`}
        aria-invalid={invalid || undefined}
        value={text}
        onChange={(e) => {
          setText(e.target.value)
          setInvalid(false)
        }}
        onBlur={() => {
          // Leaving a field that isn't a valid value cancels it rather than saving something odd.
          const ok = text.trim() === '' || parseProgress(text, byPages) !== null
          finish(ok)
        }}
        onKeyDown={(e) => {
          if (e.key === 'Enter') {
            e.preventDefault()
            finish(true)
          } else if (e.key === 'Escape') {
            e.preventDefault()
            finish(false)
          }
        }}
      />
      <span aria-hidden="true">{byPages ? `of ${String(book.pageCount)}` : '%'}</span>
    </span>
  )
}

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
