import { useEffect, useRef, useState, useSyncExternalStore, type PointerEvent } from 'react'
import type { Book } from '../sync/model'
import type { LibraryStore } from '../sync/store'
import { useToast } from '../ui/toastContext'
import { findShelf, onShelf } from './model'
import { PickBooksDialog } from './PickBooksDialog'
import { moveOnShelf, placeAll, setPlace } from './places'
import { useBookcases } from './useBookcases'

/** One row of the shelf; its handle drags with a finger or a mouse (pointer events work on iPhone, HTML5 drag doesn't). */
function ShelfRow({
  book, index, count, onOpen, onTakeOff, onMove,
}: {
  book: Book
  index: number
  count: number
  onOpen: () => void
  onTakeOff: () => void
  onMove: (toIndex: number) => void
}) {
  const row = useRef<HTMLLIElement>(null)
  const drag = useRef<{ startY: number; height: number } | null>(null)
  const [offset, setOffset] = useState(0)
  const start = (e: PointerEvent<HTMLSpanElement>) => {
    e.currentTarget.setPointerCapture?.(e.pointerId)
    drag.current = { startY: e.clientY, height: row.current?.offsetHeight || 1 }
  }
  const move = (e: PointerEvent<HTMLSpanElement>) => {
    if (drag.current) setOffset(e.clientY - drag.current.startY)
  }
  const end = (e: PointerEvent<HTMLSpanElement>) => {
    if (!drag.current) return
    const steps = Math.round((e.clientY - drag.current.startY) / drag.current.height)
    drag.current = null
    setOffset(0)
    const target = Math.min(count - 1, Math.max(0, index + steps))
    if (target !== index) onMove(target)
  }
  const cancel = () => {
    drag.current = null
    setOffset(0)
  }
  return (
    <li ref={row} className={`place-row ${offset !== 0 ? 'place-row--dragging' : ''}`.trim()} style={offset !== 0 ? { transform: `translateY(${String(offset)}px)` } : undefined}>
      <span className="place-row__handle" aria-hidden="true" onPointerDown={start} onPointerMove={move} onPointerUp={end} onPointerCancel={cancel}>≡</span>
      <button type="button" className="quiet place-row__book" onClick={onOpen}>
        <strong>{book.title}</strong>
        <small>{[book.authors, book.lentTo !== null ? `Lent to ${book.lentTo}` : null].filter(Boolean).join(' · ')}</small>
      </button>
      <button type="button" className="quiet" aria-label={`Move ${book.title} up`} disabled={index === 0} onClick={() => onMove(index - 1)}>↑</button>
      <button type="button" className="quiet" aria-label={`Move ${book.title} down`} disabled={index === count - 1} onClick={() => onMove(index + 1)}>↓</button>
      <button type="button" className="quiet" aria-label={`Take ${book.title} off this shelf`} onClick={onTakeOff}>×</button>
    </li>
  )
}

/** A shelf's books, left to right as a list: open one, take it off, change the order, or add more. */
export function ShelfPage({
  store, shelfId, onBack, onOpenBook,
}: {
  store: LibraryStore
  shelfId: string
  onBack: () => void
  onOpenBook: (id: string) => void
}) {
  const books = useSyncExternalStore(store.subscribe, () => store.books())
  const { bookcases } = useBookcases(store)
  const toast = useToast()
  const [picking, setPicking] = useState(false)
  const place = findShelf(bookcases, shelfId)
  const gone = place === null
  useEffect(() => {
    if (gone) onBack()
  }, [gone, onBack])
  if (!place) return null
  const shelf = onShelf(books, shelfId)
  return (
    <main className="physical-page">
      <button type="button" className="quiet physical-back" onClick={onBack}>Back</button>
      <h1>Shelf {place.label}</h1>
      <p className="physical-lead">{place.bookcase.room} › {place.bookcase.name}</p>
      <button type="button" className="primary physical-add" onClick={() => setPicking(true)}>Pick from library</button>
      {shelf.length === 0 && <p className="empty">No books on this shelf yet.</p>}
      <ol className="place-list">
        {shelf.map((b, i) => (
          <ShelfRow
            key={b.id}
            book={b}
            index={i}
            count={shelf.length}
            onOpen={() => onOpenBook(b.id)}
            onMove={(to) => moveOnShelf(store, b.id, shelfId, to)}
            onTakeOff={() => {
              const { shelfId: was, shelfPosition } = b
              setPlace(store, b.id, null, null)
              toast.show('Taken off the shelf', { label: 'Undo', run: () => setPlace(store, b.id, was, shelfPosition) })
            }}
          />
        ))}
      </ol>
      {picking && (
        <PickBooksDialog
          store={store}
          bookcases={bookcases}
          shelfId={shelfId}
          onClose={() => setPicking(false)}
          onPick={(ids) => {
            setPicking(false)
            const undo = placeAll(store, ids, shelfId)
            toast.show(`${ids.length === 1 ? 'Placed' : `${String(ids.length)} books placed`} on Shelf ${place.label}`, { label: 'Undo', run: undo })
          }}
        />
      )}
    </main>
  )
}
