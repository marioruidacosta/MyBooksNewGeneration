import { useId, useState, useSyncExternalStore } from 'react'
import { Dialog } from '../community/ui'
import type { Book } from '../sync/model'
import type { LibraryStore } from '../sync/store'
import { BookcaseDrawing } from './BookcaseDrawing'
import { byRoom, MAX_BOOKCASE_NAME, onShelf, shelfIds, withoutPlace, type Bookcase } from './model'
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

/** Your home: rooms, each with its bookcases. */
export function PhysicalLibraryPage({
  store, onOpenBookcase, onAdd, onOpenUnplaced,
}: {
  store: LibraryStore
  onOpenBookcase: (id: string) => void
  onAdd: () => void
  onOpenUnplaced: () => void
}) {
  const books = useSyncExternalStore(store.subscribe, () => store.books())
  const { bookcases, renameRoom } = useBookcases(store)
  const [renaming, setRenaming] = useState<string | null>(null)
  const waiting = withoutPlace(books, bookcases, store.ebookIds()).length
  return (
    <main className="physical-page">
      <h1>Physical library</h1>
      {bookcases.length === 0 && (
        <p className="physical-lead">Recreate the bookcases in your home, then record which shelf each book is on.</p>
      )}
      {byRoom(bookcases).map(([room, cases]) => (
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
      {waiting > 0 && (
        <button type="button" className="bookcase-row bookcase-row--waiting" onClick={onOpenUnplaced}>
          <span className="bookcase-row__text">
            <strong>{counted(waiting, 'book without a place', 'books without a place')}</strong>
            <span>Put them on a shelf</span>
          </span>
        </button>
      )}
      <button type="button" className="primary physical-add" onClick={onAdd}>Add a bookcase</button>
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
