import { useId, useState, useSyncExternalStore } from 'react'
import type { LibraryStore } from '../sync/store'
import { searchKey } from '../text/keys'
import { useToast } from '../ui/toastContext'
import { findShelf, withoutPlace } from './model'
import { ShelfPicker } from './PickBooksDialog'
import { placeAll } from './places'
import { useBookcases } from './useBookcases'

/** The physical books with no shelf yet: search, choose several (a search keeps them chosen) and put them on one. */
export function WithoutPlacePage({
  store, onBack, onOpenBook,
}: {
  store: LibraryStore
  onBack: () => void
  onOpenBook: (id: string) => void
}) {
  const all = useSyncExternalStore(store.subscribe, () => store.books())
  const { bookcases } = useBookcases(store)
  const toast = useToast()
  const [picked, setPicked] = useState<string[]>([])
  const [choosing, setChoosing] = useState(false)
  const books = withoutPlace(all, bookcases, store.ebookIds()).sort((a, b) => a.title.localeCompare(b.title))
  const selected = picked.filter((id) => books.some((b) => b.id === id))
  const [query, setQuery] = useState('')
  const searchId = useId()
  const wanted = searchKey(query.trim())
  const shown = books.filter((b) => wanted === '' || searchKey(`${b.title}\n${b.authors}`).includes(wanted))
  return (
    <main className="physical-page">
      <button type="button" className="quiet physical-back" onClick={onBack}>Back</button>
      <h1>Without a place</h1>
      {books.length === 0 && <p className="empty">Every physical book has a place.</p>}
      {books.length > 0 && bookcases.length === 0 && <p className="physical-lead">Add a bookcase first, then put these books on its shelves.</p>}
      {books.length > 0 && (
        <div className="dialog__field physical-search">
          <label htmlFor={searchId}>Search your books</label>
          <input id={searchId} value={query} onChange={(e) => setQuery(e.target.value)} />
        </div>
      )}
      {selected.length > 0 && bookcases.length > 0 && (
        <button type="button" className="primary physical-add" onClick={() => setChoosing(true)}>
          Put {selected.length} on a shelf
        </button>
      )}
      {books.length > 0 && shown.length === 0 && <p className="empty">No books match your search.</p>}
      <ul className="place-list">
        {shown.map((b) => (
          <li key={b.id} className="place-row">
            <label className="check-hit">
              <input
                type="checkbox"
                aria-label={`Select ${b.title}`}
                checked={picked.includes(b.id)}
                onChange={() => setPicked((p) => (p.includes(b.id) ? p.filter((x) => x !== b.id) : [...p, b.id]))}
              />
            </label>
            <button type="button" className="quiet place-row__book" onClick={() => onOpenBook(b.id)}>
              <strong>{b.title}</strong>
              <small>{b.authors}</small>
            </button>
          </li>
        ))}
      </ul>
      {choosing && (
        <ShelfPicker
          bookcases={bookcases}
          onClose={() => setChoosing(false)}
          onPick={(shelfId) => {
            setChoosing(false)
            const undo = placeAll(store, selected, shelfId)
            setPicked([])
            setQuery('')
            const label = findShelf(bookcases, shelfId)?.label ?? ''
            toast.show(`${selected.length === 1 ? 'Placed' : `${String(selected.length)} books placed`} on Shelf ${label}`, { label: 'Undo', run: undo })
          }}
        />
      )}
    </main>
  )
}
