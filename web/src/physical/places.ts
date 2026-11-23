import type { LibraryStore } from '../sync/store'
import { BOOKCASE_PREFIX, endPosition, onShelf, parseBookcase, positionBetween, renumbered, type Bookcase } from './model'

/** The two fields of a book's place: always written, and timed, together. */
export const SHELF_FIELDS = ['shelf_id', 'shelf_position']

type Prefs = Pick<LibraryStore, 'prefKeys' | 'pref'>

/** Each live bookcase's id and stored value, unreadable ones included (for backups). */
export function storedBookcases(store: Prefs): { id: string; value: string }[] {
  return store.prefKeys(BOOKCASE_PREFIX).sort().flatMap((key) => {
    const value = store.pref(key)
    return value ? [{ id: key.slice(BOOKCASE_PREFIX.length), value }] : []
  })
}

export function bookcasesOf(store: Prefs): Bookcase[] {
  return storedBookcases(store).flatMap(({ id, value }) => parseBookcase(BOOKCASE_PREFIX + id, value) ?? [])
}

/** The book's shelf and order there; no shelf means no order. */
export function setPlace(store: LibraryStore, id: string, shelfId: string | null, position: number | null): void {
  store.edit('books', id, { shelf_id: shelfId, shelf_position: shelfId === null ? null : position }, { restamp: SHELF_FIELDS })
}

function renumber(store: LibraryStore, shelfId: string, ids: string[]) {
  const books = ids.flatMap((id) => store.book(id) ?? [])
  for (const [id, position] of renumbered(books)) {
    const book = store.book(id)
    if (book && (book.shelfId !== shelfId || book.shelfPosition !== position)) setPlace(store, id, shelfId, position)
  }
}

const others = (store: LibraryStore, shelfId: string, id: string) => onShelf(store.books(), shelfId).filter((b) => b.id !== id)

/** After every book on the shelf. Books there with no order get one first, or this one would sort before them. */
export function placeAtEnd(store: LibraryStore, id: string, shelfId: string): void {
  const shelf = others(store, shelfId, id)
  if (shelf.some((b) => b.shelfPosition === null)) renumber(store, shelfId, shelf.map((b) => b.id))
  setPlace(store, id, shelfId, endPosition(others(store, shelfId, id)))
}

/** To [toIndex] among the shelf's other books: only this book changes, unless the shelf has to be renumbered. */
export function moveOnShelf(store: LibraryStore, id: string, shelfId: string, toIndex: number): void {
  const shelf = others(store, shelfId, id)
  const index = Math.min(shelf.length, Math.max(0, toIndex))
  const before = shelf[index - 1] ?? null
  const after = shelf[index] ?? null
  const position = shelf.some((b) => b.shelfPosition === null)
    ? null
    : positionBetween(before?.shelfPosition ?? null, after?.shelfPosition ?? null)
  if (position !== null) {
    setPlace(store, id, shelfId, position)
    return
  }
  const ids = shelf.map((b) => b.id)
  ids.splice(index, 0, id)
  renumber(store, shelfId, ids)
}

/** Puts these books at the end of the shelf, in this order; returns what puts them all back. */
export function placeAll(store: LibraryStore, ids: string[], shelfId: string): () => void {
  const before = ids.flatMap((id) => store.book(id) ?? [])
  for (const id of ids) placeAtEnd(store, id, shelfId)
  return () => {
    for (const b of before) setPlace(store, b.id, b.shelfId, b.shelfPosition)
  }
}

/** Takes every book off these shelves (To buy ones too); returns how many. Nothing else about them changes. */
export function clearPlaces(store: LibraryStore, shelfIds: ReadonlySet<string>): number {
  let cleared = 0
  for (const book of store.books()) {
    if (book.shelfId !== null && shelfIds.has(book.shelfId)) {
      setPlace(store, book.id, null, null)
      cleared++
    }
  }
  return cleared
}
