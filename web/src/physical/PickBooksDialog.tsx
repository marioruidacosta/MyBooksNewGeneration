import { useId, useState, useSyncExternalStore } from 'react'
import { Dialog } from '../community/ui'
import type { LibraryStore } from '../sync/store'
import { searchKey } from '../text/keys'
import { byRoom, findShelf, type Bookcase, type ShelfPlace } from './model'

/** Search your books and tick the ones on this shelf. A book on another shelf says where, and picking it moves it. */
export function PickBooksDialog({
  store, bookcases, shelfId, onPick, onClose,
}: {
  store: LibraryStore
  bookcases: Bookcase[]
  shelfId: string
  onPick: (ids: string[]) => void
  onClose: () => void
}) {
  const books = useSyncExternalStore(store.subscribe, () => store.books())
  const [query, setQuery] = useState('')
  const [picked, setPicked] = useState<string[]>([])
  const searchId = useId()
  const ebooks = store.ebookIds()
  const wanted = searchKey(query.trim())
  const shown = books
    .filter((b) => b.status !== 'WISHLIST' && !ebooks.has(b.id) && b.shelfId !== shelfId)
    .filter((b) => wanted === '' || searchKey(`${b.title}\n${b.authors}`).includes(wanted))
    .sort((a, b) => a.title.localeCompare(b.title))
  return (
    <Dialog title="Pick from library" onClose={onClose}>
      <div className="dialog__field">
        <label htmlFor={searchId}>Search your books</label>
        <input id={searchId} value={query} onChange={(e) => setQuery(e.target.value)} />
      </div>
      {shown.length === 0 && <p className="hint">No books to add.</p>}
      <ul className="pick-list">
        {shown.map((b) => {
          const where = findShelf(bookcases, b.shelfId)?.location
          return (
            <li key={b.id}>
              <label>
                <input
                  type="checkbox"
                  checked={picked.includes(b.id)}
                  onChange={() => setPicked((p) => (p.includes(b.id) ? p.filter((x) => x !== b.id) : [...p, b.id]))}
                />
                <span>
                  <strong>{b.title}</strong>
                  <small>{[b.authors, where].filter(Boolean).join(' · ')}</small>
                </span>
              </label>
            </li>
          )
        })}
      </ul>
      <div className="dialog__actions">
        <button type="button" className="quiet" onClick={onClose}>Cancel</button>
        <button type="button" className="primary" disabled={picked.length === 0} onClick={() => onPick(picked)}>
          {picked.length === 0 ? 'Put on this shelf' : `Put ${String(picked.length)} on this shelf`}
        </button>
      </div>
    </Dialog>
  )
}

/** Every shelf, by room and bookcase. */
function allShelves(bookcases: Bookcase[]): ShelfPlace[] {
  return byRoom(bookcases)
    .flatMap(([, cases]) => cases.flatMap((c) => c.columns.flatMap((column) => column.shelves)))
    .flatMap((id) => findShelf(bookcases, id) ?? [])
}

/** Choose a shelf: for one book or several. */
export function ShelfPicker({
  bookcases, current = null, onPick, onClose,
}: {
  bookcases: Bookcase[]
  current?: string | null
  onPick: (shelfId: string) => void
  onClose: () => void
}) {
  return (
    <Dialog title="Choose a shelf" onClose={onClose}>
      <ul className="shelf-picker">
        {allShelves(bookcases).filter((p) => p.shelfId !== current).map((p) => (
          <li key={p.shelfId}><button type="button" className="quiet" onClick={() => onPick(p.shelfId)}>{p.location}</button></li>
        ))}
      </ul>
      <div className="dialog__actions">
        <button type="button" className="quiet" onClick={onClose}>Cancel</button>
      </div>
    </Dialog>
  )
}
