import { useEffect, useRef, useState, type CSSProperties } from 'react'
import { coverColorCss } from '../covers/coverColor'
import type { Book } from '../sync/model'
import { onShelf, shelfLabel, type Bookcase } from './model'

/** The width assumed until the real one is measured. */
const FIRST_WIDTH = 320
const MORE_WIDTH = 22
/** A shelf on a phone's bookcase page stays wide enough to tap. */
const SMALLEST_TAPPABLE = 44

/** The same number the Android app gets from `title.hashCode() and Int.MAX_VALUE`, so a book has the same spine on both. */
function titleHash(title: string): number {
  let hash = 0
  for (let i = 0; i < title.length; i++) hash = (Math.imul(hash, 31) + title.charCodeAt(i)) | 0
  return hash & 0x7fffffff
}

const spineWidth = (b: Book, mini: boolean) => (mini ? 3 : 7 + (titleHash(b.title) % 5))
const spineHeight = (b: Book) => 72 + (titleHash(b.title) % 26)

function shownOn(books: Book[], width: number, found: string | null, mini: boolean): Book[] {
  const available = width - 4
  const widths = books.map((b) => spineWidth(b, mini) + 1)
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
  const shown = shownOn(books, width, found, mini)
  const hidden = books.length - shown.length
  const name = `Shelf ${label}, ${books.length === 1 ? '1 book' : `${String(books.length)} books`}${hidden > 0 ? `, ${String(hidden)} not shown` : ''}`
  const inside = (
    <>
      {shown.map((b) => (
        <span
          key={b.id}
          className={`spine ${b.id === found ? 'spine--found' : ''} ${b.lentTo !== null && b.id !== found ? 'spine--away' : ''}`.trim()}
          style={{ width: spineWidth(b, mini), height: `${String(spineHeight(b))}%`, background: b.id === found ? undefined : coverColorCss(b.title) }}
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
  /** The shelf heights of the Android app: a thumbnail, the book page, the editor, the bookcase page. */
  size?: 'mini' | 'card' | 'edit' | 'full'
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
  const gap = size === 'mini' ? 1 : 3
  const fitted = (width - gap * (columns - 1)) / columns
  const columnWidth = size === 'full' ? Math.max(fitted, SMALLEST_TAPPABLE) : fitted
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
