import { useId, useState, useSyncExternalStore } from 'react'
import { createPortal } from 'react-dom'
import type { Book } from '../sync/model'
import type { LibraryStore } from '../sync/store'
import { useToast } from '../ui/toastContext'
import { BookcaseDrawing } from './BookcaseDrawing'
import { findShelf, onShelf, ordinal } from './model'
import { ShelfPicker } from './PickBooksDialog'
import { placeAll, setPlace } from './places'
import { useBookcases } from './useBookcases'

/** Where to go to find this book, and the way to put it there. A To buy book has no place. */
export function WhereItIs({
  store, book, onOpenShelf, onAddBookcase,
}: {
  store: LibraryStore
  book: Book
  onOpenShelf?: (shelfId: string) => void
  onAddBookcase?: () => void
}) {
  const books = useSyncExternalStore(store.subscribe, () => store.books())
  const { bookcases } = useBookcases(store)
  const toast = useToast()
  const [choosing, setChoosing] = useState(false)
  const titleId = useId()
  if (book.status === 'WISHLIST') return null
  const place = findShelf(bookcases, book.shelfId)
  const index = place && book.shelfPosition !== null ? onShelf(books, place.shelfId).findIndex((b) => b.id === book.id) : -1
  return (
    <section className="where-it-is" aria-labelledby={titleId}>
      <h3 id={titleId}>Where it is</h3>
      {place ? (
        <>
          <button type="button" className="quiet where-it-is__place" onClick={() => onOpenShelf?.(place.shelfId)}>
            {place.location}
          </button>
          {index >= 0 && <p>{ordinal(index + 1)} from the left</p>}
          <BookcaseDrawing bookcase={place.bookcase} books={books} size="mini" highlightShelf={place.shelfId} highlightBook={book.id} />
          <div className="where-it-is__actions">
            <button type="button" onClick={() => setChoosing(true)}>Move</button>
            <button
              type="button"
              className="quiet"
              onClick={() => {
                const { shelfId, shelfPosition } = book
                setPlace(store, book.id, null, null)
                toast.show('Taken off the shelf', { label: 'Undo', run: () => setPlace(store, book.id, shelfId, shelfPosition) })
              }}
            >
              Take off the shelf
            </button>
          </div>
        </>
      ) : bookcases.length === 0 ? (
        <button type="button" onClick={() => onAddBookcase?.()}>Add a bookcase</button>
      ) : (
        <button type="button" onClick={() => setChoosing(true)}>Put on a shelf</button>
      )}
      {/* On the body: the summary panel scrolls and would clip a dialog. */}
      {choosing && createPortal(
        <ShelfPicker
          bookcases={bookcases}
          current={place?.shelfId ?? null}
          onClose={() => setChoosing(false)}
          onPick={(shelfId) => {
            setChoosing(false)
            const undo = placeAll(store, [book.id], shelfId)
            toast.show(`Placed on Shelf ${findShelf(bookcases, shelfId)?.label ?? ''}`, { label: 'Undo', run: undo })
          }}
        />,
        document.body,
      )}
    </section>
  )
}
