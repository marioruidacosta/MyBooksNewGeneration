import { useEffect, useId, useMemo, useRef, useState } from 'react'
import { normalizeIsbn } from '../add/lookup'
import { CoverImage } from '../covers/CoverImage'
import { matchesQuery, plural, STATUS_LABELS } from '../library/filters'
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
    const isbn = normalizeIsbn(query)
    // A typed ISBN finds its book whatever the hyphens and whether it is a 10 or a 13; that book comes first.
    const owner = isbn === null ? undefined : books.find((b) => b.isbn === isbn)
    const found: Row[] = books
      .filter((b) => b === owner || matchesQuery(b, query))
      .sort((a, b) => Number(b === owner) - Number(a === owner))
      .map((book) => ({ kind: 'book', book }))
    const addRow: Row[] = isbn !== null && owner === undefined ? [{ kind: 'isbn', isbn }] : []
    // An ISBN row comes first and counts toward the eight.
    return [...addRow, ...found].slice(0, MAX_ROWS)
  }, [books, query])
  // The highlighted row stays inside the list when the matches change.
  const current = Math.min(active, Math.max(0, rows.length - 1))
  const bookCount = rows.filter((r) => r.kind === 'book').length
  const status =
    query.trim() === ''
      ? ''
      : bookCount > 0
        ? `${plural(bookCount, 'book')} found`
        : rows.length > 0
          ? 'No book has that ISBN.'
          : 'No book matches.'

  useEffect(() => {
    if (open && rows.length > 0) document.getElementById(`${listId}-${String(current)}`)?.scrollIntoView?.({ block: 'nearest' })
  }, [open, rows.length, current, listId])

  // Escape closes the box wherever the focus is.
  useEffect(() => {
    if (!open) return
    const onKey = (e: KeyboardEvent) => {
      if (e.key !== 'Escape') return
      e.preventDefault()
      setOpen(false)
      setQuery('')
      setActive(0)
    }
    document.addEventListener('keydown', onKey)
    return () => document.removeEventListener('keydown', onKey)
  }, [open])

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
            onMouseDown={(e) => {
              // A click inside the box never takes the focus out of the field.
              if (e.target !== inputRef.current) {
                e.preventDefault()
                inputRef.current?.focus()
              }
            }}
            onKeyDown={(e) => {
              if (e.key === 'Tab') {
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
              aria-activedescendant={rows.length > 0 ? optionId(current) : undefined}
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
                  setActive(Math.min(rows.length - 1, current + 1))
                } else if (e.key === 'ArrowUp') {
                  e.preventDefault()
                  setActive(Math.max(0, current - 1))
                } else if (e.key === 'Enter') {
                  e.preventDefault()
                  choose(rows[current])
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
                    aria-selected={i === current}
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
                    aria-selected={i === current}
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
            <p role="status" className={rows.length === 0 && status !== '' ? 'jump-empty' : 'visually-hidden'}>
              {status}
            </p>
          </div>
        </div>
      )}
    </>
  )
}
