import { useEffect, useSyncExternalStore } from 'react'
import type { LibraryStore } from '../sync/store'
import { BookcaseDrawing } from './BookcaseDrawing'
import { onShelf, shelfIds } from './model'
import { useBookcases } from './useBookcases'

/** One bookcase from the front. A spine is too small to tap on a phone, so a shelf opens its list of books. */
export function BookcasePage({
  store, id, onBack, onEdit, onOpenShelf,
}: {
  store: LibraryStore
  id: string
  onBack: () => void
  onEdit: () => void
  onOpenShelf: (shelfId: string) => void
}) {
  const books = useSyncExternalStore(store.subscribe, () => store.books())
  const { bookcases } = useBookcases(store)
  const bookcase = bookcases.find((b) => b.id === id)
  const gone = bookcase === undefined
  useEffect(() => {
    if (gone) onBack()
  }, [gone, onBack])
  if (!bookcase) return null
  const count = shelfIds(bookcase).reduce((sum, s) => sum + onShelf(books, s).length, 0)
  return (
    <main className="physical-page">
      <button type="button" className="quiet physical-back" onClick={onBack}>Back</button>
      <div className="physical-room__head physical-case__head">
        <h1>{bookcase.name}</h1>
        {!bookcase.readOnly && <button type="button" onClick={onEdit}>Edit bookcase</button>}
      </div>
      <p className="physical-lead">{bookcase.room} · {count === 1 ? '1 book' : `${String(count)} books`}</p>
      <BookcaseDrawing bookcase={bookcase} books={books} labels onShelf={onOpenShelf} />
      <p className="hint physical-hint">Choose a shelf to see and arrange its books.</p>
    </main>
  )
}
