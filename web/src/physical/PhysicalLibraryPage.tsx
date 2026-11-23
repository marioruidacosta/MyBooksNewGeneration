import { useEffect, useId, useState, useSyncExternalStore } from 'react'
import { Dialog } from '../community/ui'
import type { Book } from '../sync/model'
import type { LibraryStore } from '../sync/store'
import { BookcaseDrawing } from './BookcaseDrawing'
import { byRoom, findInHome, MAX_BOOKCASE_NAME, onShelf, ordinal, shelfIds, withoutPlace, type Bookcase, type FoundBook } from './model'
import { useBookcases } from './useBookcases'

const counted = (n: number, one: string, many: string) => (n === 1 ? `1 ${one}` : `${String(n)} ${many}`)

function BookcaseRow({ bookcase, books, onOpen }: { bookcase: Bookcase; books: Book[]; onOpen: () => void }) {
  const shelves = shelfIds(bookcase)
  const count = shelves.reduce((sum, id) => sum + onShelf(books, id).length, 0)
  return (
    <li>
      <button type="button" className="bookcase-row" onClick={onOpen}>
        <span className="bookcase-row__drawing"><BookcaseDrawing bookcase={bookcase} books={books} size="mini" /></span>
        <span className="bookcase-row__text">
          <strong>{bookcase.name}</strong>
          <span>{counted(shelves.length, 'shelf', 'shelves')} · {counted(count, 'book', 'books')}</span>
        </span>
      </button>
    </li>
  )
}

function RenameRoom({ room, onRename, onClose }: { room: string; onRename: (name: string) => void; onClose: () => void }) {
  const [name, setName] = useState('')
  const id = useId()
  return (
    <Dialog title={`Rename ${room}`} onClose={onClose}>
      <div className="dialog__field">
        <label htmlFor={id}>Room name</label>
        <input id={id} value={name} maxLength={MAX_BOOKCASE_NAME} onChange={(e) => setName(e.target.value)} />
      </div>
      <div className="dialog__actions">
        <button type="button" className="quiet" onClick={onClose}>Cancel</button>
        <button type="button" className="primary" disabled={name.trim() === ''} onClick={() => onRename(name)}>Rename</button>
      </div>
    </Dialog>
  )
}

/** A book found by the search, and where to go to get it. */
function FoundRow({ found, onOpen }: { found: FoundBook; onOpen: () => void }) {
  const { book, place, fromLeft } = found
  const where = place
    ? [place.location, fromLeft === null ? null : `${ordinal(fromLeft)} from the left`].filter(Boolean).join(' · ')
    : 'Without a place'
  return (
    <li>
      <button type="button" className="bookcase-row" onClick={onOpen}>
        <span className="bookcase-row__text">
          <strong>{book.title}</strong>
          {book.authors !== '' && <span>{book.authors}</span>}
          <span className="found-where">{where}</span>
          {book.lentTo !== null && <span>Lent to {book.lentTo}</span>}
        </span>
      </button>
    </li>
  )
}

/** The search to show again when coming back from a book opened from its results; any other visit starts empty. */
const searchToReturnTo = new WeakMap<LibraryStore, string>()

/** Your home: rooms, each with its bookcases, and a search that says where a book is. */
export function PhysicalLibraryPage({
  store, onOpenBook, onOpenBookcase, onAdd, onOpenUnplaced,
}: {
  store: LibraryStore
  onOpenBook: (id: string) => void
  onOpenBookcase: (id: string) => void
  onAdd: () => void
  onOpenUnplaced: () => void
}) {
  const books = useSyncExternalStore(store.subscribe, () => store.books())
  const { bookcases, renameRoom } = useBookcases(store)
  const [renaming, setRenaming] = useState<string | null>(null)
  const waiting = withoutPlace(books, bookcases, store.ebookIds()).length
  const [query, setQuery] = useState(() => searchToReturnTo.get(store) ?? '')
  useEffect(() => {
    searchToReturnTo.delete(store)
  }, [store])
  const searchId = useId()
  const searching = query.trim() !== ''
  const found = findInHome(books, bookcases, store.ebookIds(), query)
  return (
    <main className="physical-page">
      <h1>Physical library</h1>
      {books.length > 0 && (
        <div className="dialog__field physical-search">
          <label htmlFor={searchId}>Find a book by title or author</label>
          <input id={searchId} value={query} onChange={(e) => setQuery(e.target.value)} />
        </div>
      )}
      {searching && found.length === 0 && <p className="empty">No books match your search.</p>}
      {searching && (
        <ul className="found-list">
          {found.map((f) => (
            <FoundRow
              key={f.book.id}
              found={f}
              onOpen={() => {
                searchToReturnTo.set(store, query)
                onOpenBook(f.book.id)
              }}
            />
          ))}
        </ul>
      )}
      {!searching && bookcases.length === 0 && (
        <p className="physical-lead">Recreate the bookcases in your home, then record which shelf each book is on.</p>
      )}
      {!searching && byRoom(bookcases).map(([room, cases]) => (
        <section key={room} className="physical-room">
          <div className="physical-room__head">
            <h2>{room}</h2>
            <button type="button" className="quiet" aria-label={`Rename room ${room}`} onClick={() => setRenaming(room)}>Rename</button>
          </div>
          <ul className="bookcase-list">
            {cases.map((c) => <BookcaseRow key={c.id} bookcase={c} books={books} onOpen={() => onOpenBookcase(c.id)} />)}
          </ul>
        </section>
      ))}
      {!searching && waiting > 0 && (
        <button type="button" className="bookcase-row bookcase-row--waiting" onClick={onOpenUnplaced}>
          <span className="bookcase-row__text">
            <strong>{counted(waiting, 'book without a place', 'books without a place')}</strong>
            <span>Put them on a shelf</span>
          </span>
        </button>
      )}
      {!searching && <button type="button" className="primary physical-add" onClick={onAdd}>Add a bookcase</button>}
      {renaming !== null && (
        <RenameRoom
          room={renaming}
          onRename={(name) => {
            renameRoom(renaming, name)
            setRenaming(null)
          }}
          onClose={() => setRenaming(null)}
        />
      )}
    </main>
  )
}
