import type { Book } from '../sync/model'

/** A bookcase is one synced preference, `bookcase.<uuid>`. The Android app reads and writes the same JSON. */
export const BOOKCASE_PREFIX = 'bookcase.'
export const MAX_BOOKCASE_NAME = 60
export const MAX_COLUMNS = 10
export const MAX_SHELVES = 15

const FORMAT = 1
const NO_ROOM = 'No room'
/** Two positions closer than this have no usable midpoint: the shelf is renumbered instead. */
const SMALLEST_GAP = 1e-6

/** A vertical section of a bookcase; its shelves from the top. */
export type BookcaseColumn = { id: string; shelves: string[] }

/** [readOnly]: written by a newer version of the app, so it is shown but never saved back. */
export type Bookcase = { id: string; name: string; room: string; columns: BookcaseColumn[]; readOnly: boolean }

/** Where a shelf is: column and shelf count from 0. */
export type ShelfPlace = { bookcase: Bookcase; column: number; shelf: number; shelfId: string; label: string; location: string }

const uuid = () => crypto.randomUUID()
const clamp = (n: number, low: number, high: number) => Math.min(high, Math.max(low, n))

/** Columns are A, B, C from the left and shelves count from the top: "A2". */
export function shelfLabel(column: number, shelf: number): string {
  return `${String.fromCharCode(65 + column)}${String(shelf + 1)}`
}

export function newBookcase(name: string, room: string, columns: number, shelves: number, newId: () => string = uuid): Bookcase {
  const each = clamp(shelves, 1, MAX_SHELVES)
  const id = newId()
  return {
    id,
    name: name.trim().slice(0, MAX_BOOKCASE_NAME),
    room: room.trim().slice(0, MAX_BOOKCASE_NAME),
    readOnly: false,
    columns: Array.from({ length: clamp(columns, 1, MAX_COLUMNS) }, () => ({
      id: newId(),
      shelves: Array.from({ length: each }, () => newId()),
    })),
  }
}

export function toStored(b: Bookcase): string {
  return JSON.stringify({
    v: FORMAT, name: b.name, room: b.room, columns: b.columns.map((c) => ({ id: c.id, shelves: c.shelves })),
  })
}

const isObject = (v: unknown): v is Record<string, unknown> => typeof v === 'object' && v !== null && !Array.isArray(v)

/** The bookcase stored under [key]; null when the value can't be read. */
export function parseBookcase(key: string, value: string): Bookcase | null {
  if (!key.startsWith(BOOKCASE_PREFIX)) return null
  const id = key.slice(BOOKCASE_PREFIX.length)
  if (id === '') return null
  let raw: unknown
  try {
    raw = JSON.parse(value)
  } catch {
    return null
  }
  if (!isObject(raw) || !Array.isArray(raw.columns) || raw.columns.length === 0) return null
  const columns: BookcaseColumn[] = []
  for (const c of raw.columns as unknown[]) {
    if (!isObject(c) || typeof c.id !== 'string' || !Array.isArray(c.shelves) || c.shelves.length === 0) return null
    if (!c.shelves.every((s) => typeof s === 'string')) return null
    columns.push({ id: c.id, shelves: c.shelves as string[] })
  }
  const version = typeof raw.v === 'number' ? raw.v : FORMAT
  const room = typeof raw.room === 'string' ? raw.room.trim() : ''
  return {
    id,
    name: typeof raw.name === 'string' ? raw.name : '',
    room: room === '' ? NO_ROOM : room,
    columns,
    readOnly: version > FORMAT,
  }
}

export function shelfIds(b: Bookcase): string[] {
  return b.columns.flatMap((c) => c.shelves)
}

/** A new shelf at the bottom of [column]; nothing changes when the column is full. */
export function withShelfAdded(b: Bookcase, column: number, newId: () => string = uuid): Bookcase {
  const target = b.columns.at(column)
  if (column < 0 || !target || target.shelves.length >= MAX_SHELVES) return b
  return { ...b, columns: b.columns.map((c, i) => (i === column ? { ...c, shelves: [...c.shelves, newId()] } : c)) }
}

/** Without that shelf; a column keeps at least one. */
export function withoutShelf(b: Bookcase, shelfId: string): Bookcase {
  const target = b.columns.find((c) => c.shelves.includes(shelfId))
  if (!target || target.shelves.length <= 1) return b
  return { ...b, columns: b.columns.map((c) => (c === target ? { ...c, shelves: c.shelves.filter((s) => s !== shelfId) } : c)) }
}

export function withColumnAdded(b: Bookcase, shelves: number, newId: () => string = uuid): Bookcase {
  if (b.columns.length >= MAX_COLUMNS) return b
  const column = { id: newId(), shelves: Array.from({ length: clamp(shelves, 1, MAX_SHELVES) }, () => newId()) }
  return { ...b, columns: [...b.columns, column] }
}

/** Without that column; a bookcase keeps at least one. */
export function withoutColumn(b: Bookcase, column: number): Bookcase {
  if (b.columns.length <= 1 || column < 0 || column >= b.columns.length) return b
  return { ...b, columns: b.columns.filter((_, i) => i !== column) }
}

export function findShelf(cases: Bookcase[], shelfId: string | null): ShelfPlace | null {
  if (shelfId === null) return null
  for (const bookcase of cases) {
    for (let column = 0; column < bookcase.columns.length; column++) {
      const shelf = bookcase.columns[column].shelves.indexOf(shelfId)
      if (shelf >= 0) {
        const label = shelfLabel(column, shelf)
        return { bookcase, column, shelf, shelfId, label, location: `${bookcase.room} › ${bookcase.name} › Shelf ${label}` }
      }
    }
  }
  return null
}

const byText = (a: string, b: string) => (a < b ? -1 : a > b ? 1 : 0)

/** Rooms by name, each with its bookcases by name. Capitals don't make a different room. */
export function byRoom(cases: Bookcase[]): [string, Bookcase[]][] {
  const rooms = new Map<string, Bookcase[]>()
  for (const c of cases) {
    const key = c.room.toLowerCase()
    rooms.set(key, [...(rooms.get(key) ?? []), c])
  }
  return [...rooms.entries()]
    .sort(([a], [b]) => byText(a, b))
    .map(([, list]) => [list[0].room, [...list].sort((a, b) => byText(a.name.toLowerCase(), b.name.toLowerCase()))])
}

/** The books on a shelf, left to right. A To buy book isn't on any shelf, even if it keeps its place. */
export function onShelf(books: Book[], shelfId: string): Book[] {
  return books
    .filter((b) => b.shelfId === shelfId && b.status !== 'WISHLIST')
    .sort((a, b) => {
      if ((a.shelfPosition === null) !== (b.shelfPosition === null)) return a.shelfPosition === null ? 1 : -1
      if (a.shelfPosition !== null && b.shelfPosition !== null && a.shelfPosition !== b.shelfPosition) {
        return a.shelfPosition - b.shelfPosition
      }
      return byText(a.title.toLowerCase(), b.title.toLowerCase()) || byText(a.id, b.id)
    })
}

/** Physical books with no real shelf: not To buy, not an ebook, and on no shelf that still exists. */
export function withoutPlace(books: Book[], cases: Bookcase[], ebookIds: ReadonlySet<string>): Book[] {
  const shelves = new Set(cases.flatMap(shelfIds))
  return books.filter((b) => b.status !== 'WISHLIST' && !ebookIds.has(b.id) && (b.shelfId === null || !shelves.has(b.shelfId)))
}

/** The position after every book on the shelf. */
export function endPosition(shelfBooks: Book[]): number {
  const positions = shelfBooks.flatMap((b) => (b.shelfPosition === null ? [] : [b.shelfPosition]))
  return positions.length === 0 ? 1 : Math.max(...positions) + 1
}

/** A position between two neighbours (null: that side is the end of the shelf); null when there is no room left. */
export function positionBetween(before: number | null, after: number | null): number | null {
  if (before === null && after === null) return 1
  if (before === null) return (after as number) - 1
  if (after === null) return before + 1
  return after - before < SMALLEST_GAP ? null : (before + after) / 2
}

/** Whole positions in the order the books are shown. */
export function renumbered(shelfBooks: Book[]): Map<string, number> {
  return new Map(shelfBooks.map((b, i) => [b.id, i + 1]))
}
