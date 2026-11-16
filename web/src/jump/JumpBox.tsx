import { useEffect, useId, useMemo, useRef, useState } from 'react'
import { normalizeIsbn } from '../add/lookup'
import { CoverImage } from '../covers/CoverImage'
import { matchesQuery, STATUS_LABELS } from '../library/filters'
import type { Book } from '../sync/model'

const MAX_ROWS = 8

type Row = { kind: 'book'; book: Book } | { kind: 'isbn'; isbn: string }

type Props = {
  books: Book[]
  onOpen: (id: string) => void
  /** A valid ISBN no book has: go to the Add page and look it up. */
  onAddIsbn: (isbn: string) => void
}

const isMac = () => typeof navigator !== 'undefined' && /Mac|iPhone|iPad/.test(navigator.platform)

/** The "Ctrl K" hint in the app bar and the box it opens: type a title, an author or an ISBN, Enter goes there. */
export function JumpBox({ books, onOpen, onAddIsbn }: Props) {
  const [open, setOpen] = useState(false)
  const [query, setQuery] = useState('')
  const [active, setActive] = useState(0)
  const inputRef = useRef<HTMLInputElement>(null)
  const returnTo = useRef<HTMLElement | null>(null)
  const listId = useId()

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if ((e.ctrlKey || e.metaKey) && !e.altKey && e.key.toLowerCase() === 'k') {
        e.preventDefault()
        setOpen(true)
      }
    }
    document.addEventListener('keydown', onKey)
    return () => document.removeEventListener('keydown', onKey)
  }, [])

  useEffect(() => {
    if (!open) return
    returnTo.current = document.activeElement instanceof HTMLElement ? document.activeElement : null
    inputRef.current?.focus()
    return () => returnTo.current?.focus()
  }, [open])

  const rows = useMemo<Row[]>(() => {
    if (query.trim() === '') return []
    const found: Row[] = books.filter((b) => matchesQuery(b, query)).map((book) => ({ kind: 'book', book }))
    const isbn = normalizeIsbn(query)
    const addRow: Row[] = isbn !== null && !books.some((b) => b.isbn === isbn) ? [{ kind: 'isbn', isbn }] : []
    // An ISBN row comes first and counts toward the eight.
    return [...addRow, ...found].slice(0, MAX_ROWS)
  }, [books, query])

  const close = () => {
    setOpen(false)
    setQuery('')
    setActive(0)
  }

  const choose = (row: Row | undefined) => {
    if (!row) return
    close()
    if (row.kind === 'isbn') onAddIsbn(row.isbn)
    else onOpen(row.book.id)
  }

  const optionId = (i: number) => `${listId}-${String(i)}`
  const hint = isMac() ? '⌘ K' : 'Ctrl K'

  return (
    <>
      <button type="button" className="jump-hint quiet" aria-label="Jump to a book" onClick={() => setOpen(true)}>
        <kbd>{hint}</kbd>
      </button>
      {open && (
        <div className="jump-backdrop" onMouseDown={(e) => e.target === e.currentTarget && close()}>
          <div
            className="jump-box"
            role="dialog"
            aria-modal="true"
            aria-label="Jump to a book"
            onKeyDown={(e) => {
              if (e.key === 'Escape') {
                e.preventDefault()
                close()
              } else if (e.key === 'Tab') {
                // The box holds the only field; the list is walked with the arrows.
                e.preventDefault()
              }
            }}
          >
            <input
              ref={inputRef}
              type="text"
              role="combobox"
              aria-label="Jump to a book"
              aria-expanded={rows.length > 0}
              aria-controls={listId}
              aria-activedescendant={rows.length > 0 ? optionId(active) : undefined}
              aria-autocomplete="list"
              autoComplete="off"
              placeholder="Title, author or ISBN"
              value={query}
              onChange={(e) => {
                setQuery(e.target.value)
                setActive(0)
              }}
              onKeyDown={(e) => {
                if (e.key === 'ArrowDown') {
                  e.preventDefault()
                  setActive((a) => Math.min(rows.length - 1, a + 1))
                } else if (e.key === 'ArrowUp') {
                  e.preventDefault()
                  setActive((a) => Math.max(0, a - 1))
                } else if (e.key === 'Enter') {
                  e.preventDefault()
                  choose(rows[active])
                }
              }}
            />
            <ul id={listId} role="listbox" aria-label="Matching books" className="jump-list">
              {rows.map((row, i) =>
                row.kind === 'isbn' ? (
                  <li
                    key="isbn"
                    id={optionId(i)}
                    role="option"
                    aria-selected={i === active}
                    className="jump-row jump-row--add"
                    onMouseMove={() => setActive(i)}
                    onClick={() => choose(row)}
                  >
                    Add ISBN {row.isbn}
                  </li>
                ) : (
                  <li
                    key={row.book.id}
                    id={optionId(i)}
                    role="option"
                    aria-selected={i === active}
                    className="jump-row"
                    onMouseMove={() => setActive(i)}
                    onClick={() => choose(row)}
                  >
                    <CoverImage book={row.book} compact className="jump-cover" />
                    <span className="jump-text">
                      <span className="jump-title">{row.book.title}</span>
                      <span className="jump-author">{row.book.authors}</span>
                    </span>
                    <span className="jump-status">{STATUS_LABELS[row.book.status]}</span>
                  </li>
                ),
              )}
            </ul>
            {query.trim() !== '' && rows.length === 0 && <p className="jump-empty">No book matches.</p>}
          </div>
        </div>
      )}
    </>
  )
}
