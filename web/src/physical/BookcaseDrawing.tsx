import { useEffect, useRef, useState, type CSSProperties } from 'react'
import { coverColorCss, coverColorIndex } from '../covers/coverColor'
import type { Book } from '../sync/model'
import { onShelf, shelfLabel, type Bookcase } from './model'

/** The width assumed until the real one is measured. */
const FIRST_WIDTH = 320
const MORE_WIDTH = 24
const GAP = 3

const spineWidth = (b: Book) => 7 + (coverColorIndex(b.title) % 5)
const spineHeight = (b: Book) => 72 + ((coverColorIndex(b.title) * 7) % 26)

function shownOn(books: Book[], width: number, found: string | null): Book[] {
  const available = width - 4
  const widths = books.map((b) => spineWidth(b) + 1)
  const total = widths.reduce((a, b) => a + b, 0)
  const room = total <= available ? available : available - MORE_WIDTH
  let used = 0
  let fit = 0
  for (const w of widths) {
    if (used + w > room) break
    used += w
    fit++
  }
  // The book being pointed out is always drawn, even past the edge of a full shelf.
  const index = books.findIndex((b) => b.id === found)
  return index >= fit && fit > 0 ? [...books.slice(0, fit - 1), books[index]] : books.slice(0, fit)
}

function Shelf({
  label, books, width, labels, mini, highlighted, found, onClick,
}: {
  label: string
  books: Book[]
  width: number
  labels: boolean
  mini: boolean
  highlighted: boolean
  found: string | null
  onClick?: () => void
}) {
  const shown = shownOn(books, width, found)
  const hidden = books.length - shown.length
  const name = `Shelf ${label}, ${books.length === 1 ? '1 book' : `${String(books.length)} books`}${hidden > 0 ? `, ${String(hidden)} not shown` : ''}`
  const inside = (
    <>
      {shown.map((b) => (
        <span
          key={b.id}
          className={`spine ${b.id === found ? 'spine--found' : ''} ${b.lentTo !== null && b.id !== found ? 'spine--away' : ''}`.trim()}
          style={{ width: spineWidth(b), height: `${String(spineHeight(b))}%`, background: b.id === found ? undefined : coverColorCss(b.title) }}
        >
          {!mini && <span className="spine__title">{b.title}</span>}
        </span>
      ))}
      {hidden > 0 && !mini && <span className="bookcase__more">+{hidden}</span>}
      {labels && <span className="bookcase__label">{label}</span>}
    </>
  )
  const className = `bookcase__shelf ${highlighted ? 'bookcase__shelf--on' : ''}`.trim()
  if (onClick) return <button type="button" className={className} aria-label={name} onClick={onClick}>{inside}</button>
  if (labels) return <div className={className} role="img" aria-label={name}>{inside}</div>
  return <div className={className} aria-hidden="true">{inside}</div>
}

/** A bookcase from the front: columns side by side, each shelf with its books as spines, left to right. */
export function BookcaseDrawing({
  bookcase, books, labels = false, highlightShelf = null, highlightBook = null, onShelf: open, size = 'full',
}: {
  bookcase: Bookcase
  books: Book[]
  labels?: boolean
  highlightShelf?: string | null
  highlightBook?: string | null
  onShelf?: (shelfId: string) => void
  size?: 'mini' | 'full'
}) {
  const box = useRef<HTMLDivElement>(null)
  const [width, setWidth] = useState(FIRST_WIDTH)
  useEffect(() => {
    if (!box.current || typeof ResizeObserver === 'undefined') return
    const observer = new ResizeObserver(([entry]) => setWidth(entry.contentRect.width))
    observer.observe(box.current)
    return () => observer.disconnect()
  }, [])
  const columns = bookcase.columns.length
  const columnWidth = (width - GAP * (columns - 1)) / columns
  const rows = Math.max(...bookcase.columns.map((c) => c.shelves.length))
  return (
    <div ref={box} className={`bookcase bookcase--${size}`} style={{ '--rows': rows, '--columns': columns } as CSSProperties}>
      {bookcase.columns.map((column, c) => (
        // A column with fewer shelves has taller ones, as in the furniture.
        <div key={column.id} className="bookcase__column">
          {column.shelves.map((shelfId, s) => (
            <Shelf
              key={shelfId}
              label={shelfLabel(c, s)}
              books={onShelf(books, shelfId)}
              width={columnWidth}
              labels={labels}
              mini={size === 'mini'}
              highlighted={shelfId === highlightShelf}
              found={highlightBook}
              onClick={open ? () => open(shelfId) : undefined}
            />
          ))}
        </div>
      ))}
    </div>
  )
}
