import 'fake-indexeddb/auto'
import { IDBFactory } from 'fake-indexeddb'
import { strToU8, unzipSync } from 'fflate'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { backupItems, planImport, applyPlan } from '../io/importPlan'
import { parseBackup, writeBackup } from '../io/backup'
import { blobBytes } from '../io/blobBytes'
import { publicFieldsDiffer, publicState } from '../share/publisher'
import { rowKey, type SyncApi } from '../sync/api'
import { bookToFields, rowToBook } from '../sync/codec'
import type { Book, SyncRow } from '../sync/model'
import { LibraryStore } from '../sync/store'
import { combineRows, withShelfPair } from '../sync/twin'
import { mergeBooks, planMerge } from '../tidy/mergeBooks'
import { BOOKCASE_PREFIX, onShelf, toStored, type Bookcase } from './model'
import { bookcasesOf, clearPlaces, moveOnShelf, placeAtEnd, setPlace, storedBookcases } from './places'

const api: SyncApi = {
  pull: async () => ({ rows: [], last: 0 }),
  push: async (changes) => ({ applied: changes.map((c) => rowKey(c.table, c.id)), rejected: [] }),
  onPing: () => () => {},
}

const windowCase: Bookcase = {
  id: 'w', name: 'By the window', room: 'Living room', readOnly: false,
  columns: [{ id: 'c1', shelves: ['s1', 's2'] }],
}

const book = (id: string, over: Partial<Book> = {}): Book => ({
  id, isbn: null, title: id, authors: 'Someone', publisher: null, publishedYear: null, pageCount: null, coverUrl: null,
  description: null, status: 'READ', rating: null, notes: '', dateAdded: 1, dateRead: null, dateStarted: null, currentPage: null,
  progressPercent: null, review: '', tags: [], series: null, seriesNumber: null, lentTo: null, lentOn: null, shelfId: null,
  shelfPosition: null, deleted: false, ...over,
})

let store: LibraryStore

beforeEach(async () => {
  globalThis.indexedDB = new IDBFactory()
  store = new LibraryStore({ api, owner: 'me', pollMs: 0, retryDelays: [60_000] })
  await store.load()
})
afterEach(async () => {
  vi.restoreAllMocks()
  await store.close()
})

const order = (shelf: string) => onShelf(store.books(), shelf).map((b) => b.id)

describe('a book\'s place', () => {
  it('codecCarriesThePlace', () => {
    const fields = bookToFields(book('b', { shelfId: 's1', shelfPosition: 2.5 }))
    expect(fields.shelf_id).toBe('s1')
    expect(fields.shelf_position).toBe(2.5)
    const back = rowToBook({ table: 'books', id: 'b', fields, times: {} })
    expect([back.shelfId, back.shelfPosition]).toEqual(['s1', 2.5])
  })

  it('aRowFromAnOlderAppHasNoPlace', () => {
    const back = rowToBook({ table: 'books', id: 'b', fields: { title: 'Old', status: 'READ' }, times: {} })
    expect([back.shelfId, back.shelfPosition]).toEqual([null, null])
  })

  it('publicStateHasNoPlace', () => {
    const placed = book('b', { shelfId: 's1', shelfPosition: 2 })
    expect(JSON.stringify(publicState(placed))).not.toContain('shelf')
    expect(publicFieldsDiffer(book('b'), placed)).toBe(false)
  })

  it('setPlaceStampsBothFields', () => {
    store.create(book('b'))
    const now = vi.spyOn(Date, 'now').mockReturnValue(5_000)
    setPlace(store, 'b', 's1', 1)
    now.mockReturnValue(9_000)
    setPlace(store, 'b', 's1', 2)
    const times = store.bookTimes('b')
    expect(times.shelf_id).toBe(times.shelf_position)
    expect(times.shelf_id).toBeGreaterThanOrEqual(9_000)
  })

  it('noShelfMeansNoOrder', () => {
    store.create(book('b', { shelfId: 's1', shelfPosition: 3 }))
    setPlace(store, 'b', null, 3)
    expect(store.book('b')?.shelfPosition).toBeNull()
  })

  it('placingGoesToTheEndEvenBesideUnorderedBooks', () => {
    store.create(book('old', { shelfId: 's1' }))
    store.create(book('new'))
    placeAtEnd(store, 'new', 's1')
    expect(order('s1')).toEqual(['old', 'new'])
  })

  it('movingToTheMiddleChangesOnlyThatBook', () => {
    store.create(book('a', { shelfId: 's1', shelfPosition: 1 }))
    store.create(book('b', { shelfId: 's1', shelfPosition: 2 }))
    store.create(book('c', { shelfId: 's1', shelfPosition: 3 }))
    moveOnShelf(store, 'c', 's1', 1)
    expect(order('s1')).toEqual(['a', 'c', 'b'])
    expect(store.book('c')?.shelfPosition).toBe(1.5)
    expect(store.book('b')?.shelfPosition).toBe(2)
  })

  it('movingBesideUnorderedBooksRenumbers', () => {
    for (const id of ['a', 'b', 'c']) store.create(book(id, { shelfId: 's1' }))
    moveOnShelf(store, 'c', 's1', 0)
    expect(order('s1')).toEqual(['c', 'a', 'b'])
    expect(store.books().every((b) => b.shelfPosition !== null)).toBe(true)
  })

  it('noGapLeftRenumbers', () => {
    store.create(book('a', { shelfId: 's1', shelfPosition: 1 }))
    store.create(book('b', { shelfId: 's1', shelfPosition: 1.0000001 }))
    store.create(book('c', { shelfId: 's1', shelfPosition: 2 }))
    moveOnShelf(store, 'c', 's1', 1)
    expect(order('s1')).toEqual(['a', 'c', 'b'])
  })

  it('clearPlacesKeepsEverythingElse', () => {
    store.create(book('a', { shelfId: 's1', shelfPosition: 1, status: 'READING', notes: 'keep' }))
    store.create(book('b', { shelfId: 's2', shelfPosition: 1 }))
    expect(clearPlaces(store, new Set(['s1']))).toBe(1)
    expect(store.book('a')).toMatchObject({ shelfId: null, shelfPosition: null, status: 'READING', notes: 'keep' })
    expect(store.book('b')?.shelfId).toBe('s2')
  })
})

describe('bookcases in the store', () => {
  it('readsLiveBookcasesOnly', () => {
    store.setPref(BOOKCASE_PREFIX + 'w', toStored(windowCase))
    store.setPref(BOOKCASE_PREFIX + 'gone', toStored({ ...windowCase, id: 'gone' }))
    store.setPref(BOOKCASE_PREFIX + 'gone', null)
    store.setPref(BOOKCASE_PREFIX + 'bad', 'nope')
    store.setPref('other', 'x')
    expect(bookcasesOf(store).map((b) => b.id)).toEqual(['w'])
    expect(storedBookcases(store).map((b) => b.id)).toEqual(['bad', 'w'])
  })

  it('knowsWhichBooksHaveAnEbook', () => {
    expect(store.ebookIds().size).toBe(0)
  })
})

describe('backups', () => {
  const data = (books: Book[]) => ({ books, quotes: [], challenges: [], covers: new Map<string, Uint8Array>() })

  it('backupRoundTrip', async () => {
    const file = writeBackup({ ...data([book('b', { shelfId: 's1', shelfPosition: 2.5 })]), bookcases: [{ id: 'w', value: toStored(windowCase) }] }, 1)
    const bytes = await blobBytes(file)
    const written = JSON.parse(new TextDecoder().decode(unzipSync(bytes)['library.json'])) as { bookcases: unknown[] }
    expect(written.bookcases).toEqual([{ id: 'w', value: toStored(windowCase) }])
    const back = parseBackup(bytes)
    expect([back.books[0].shelfId, back.books[0].shelfPosition]).toEqual(['s1', 2.5])
    expect(back.bookcases).toEqual([{ id: 'w', value: toStored(windowCase) }])
  })

  it('anOlderBackupHasNoLayout', () => {
    const back = parseBackup(strToU8('{"version":1,"books":[{"title":"Old"}]}'))
    expect(back.books[0].shelfId).toBeNull()
    expect(back.bookcases).toEqual([])
  })

  it('aBackupFromAndroidReads', () => {
    const android = JSON.stringify({
      version: 1, exportedAt: 5,
      books: [{ title: 'Emma', shelfId: 's2', shelfPosition: 3 }],
      bookcases: [{ id: 'w', value: toStored(windowCase) }],
    })
    const back = parseBackup(strToU8(android))
    expect([back.books[0].shelfId, back.books[0].shelfPosition]).toEqual(['s2', 3])
    expect(back.bookcases[0].id).toBe('w')
  })

  it('importingReplacesABookcaseWithTheSameIdAndKeepsOthers', async () => {
    store.setPref(BOOKCASE_PREFIX + 'w', toStored({ ...windowCase, name: 'Changed' }))
    store.setPref(BOOKCASE_PREFIX + 'z', toStored({ ...windowCase, id: 'z', name: 'Stays' }))
    const backup = parseBackup(strToU8(JSON.stringify({
      version: 1, exportedAt: 5, books: [],
      bookcases: [{ id: 'w', value: toStored(windowCase) }, { id: 'bad', value: 'nope' }],
    })))
    await applyPlan(planImport(backupItems(backup), store, 10), store, {})
    expect(bookcasesOf(store).find((b) => b.id === 'w')?.name).toBe('By the window')
    expect(bookcasesOf(store).find((b) => b.id === 'z')?.name).toBe('Stays')
    expect(store.pref(BOOKCASE_PREFIX + 'bad')).toBeNull()
  })

  it('importingABookWithoutAPlaceKeepsItsPlace', async () => {
    store.create(book('b', { isbn: '9780141439587', title: 'Emma', shelfId: 's1', shelfPosition: 2 }))
    const backup = parseBackup(strToU8(JSON.stringify({ version: 1, exportedAt: 5, books: [{ isbn: '9780141439587', title: 'Emma', authors: 'Someone' }] })))
    await applyPlan(planImport(backupItems(backup), store, 10), store, {})
    expect(store.book('b')).toMatchObject({ shelfId: 's1', shelfPosition: 2 })
  })

  it('importedPairIsNotMixed', async () => {
    store.create(book('b', { isbn: '9780141439587', title: 'Emma', shelfId: 's1', shelfPosition: 2 }))
    const backup = parseBackup(strToU8(JSON.stringify({
      version: 1, exportedAt: 5,
      books: [{ isbn: '9780141439587', title: 'Emma', authors: 'Someone', shelfId: 's9', shelfPosition: 7 }],
    })))
    await applyPlan(planImport(backupItems(backup), store, 10), store, {})
    expect(store.book('b')).toMatchObject({ shelfId: 's9', shelfPosition: 7 })
    const times = store.bookTimes('b')
    expect(times.shelf_id).toBe(times.shelf_position)
  })
})

describe('merging duplicates', () => {
  const row = (shelf: string | null, position: number | null, time: number): SyncRow => ({
    table: 'books', id: 'b',
    fields: { title: 'T', shelf_id: shelf, shelf_position: position },
    times: { title: time, shelf_id: time, shelf_position: time },
  })

  it('shelfPairFollowsThePlacedBook', () => {
    const merged = combineRows(row(null, null, 20), row('s1', 2, 10))
    expect([merged.fields.shelf_id, merged.fields.shelf_position]).toEqual(['s1', 2])
    expect(merged.times.shelf_id).toBeGreaterThan(20)
    expect(merged.times.shelf_id).toBe(merged.times.shelf_position)
  })

  it('shelfPairPrefersTheKeptBook', () => {
    const merged = combineRows(row('s1', 1, 10), row('s2', 9, 20))
    expect([merged.fields.shelf_id, merged.fields.shelf_position]).toEqual(['s1', 1])
    expect(merged.times.shelf_id).toBeGreaterThan(20)
  })

  it('shelfPairIgnoresAnInvalidShelf', () => {
    const kept = row('gone', 1, 10)
    const other = row('s2', 9, 10)
    const merged = withShelfPair(combineRows(kept, other), kept, other, (id) => id === 's2')
    expect([merged.fields.shelf_id, merged.fields.shelf_position]).toEqual(['s2', 9])
  })

  it('tidyMergeKeepsThePair', async () => {
    store.setPref(BOOKCASE_PREFIX + 'w', toStored(windowCase))
    const keep = book('keep', { shelfId: 'gone', shelfPosition: 1 })
    const drop = book('drop', { shelfId: 's2', shelfPosition: 4 })
    store.create(keep)
    store.create(drop)
    expect(planMerge(store, keep, drop).merged).toMatchObject({ shelfId: 's2', shelfPosition: 4 })
    await mergeBooks(store, keep, drop)
    expect(store.book('keep')).toMatchObject({ shelfId: 's2', shelfPosition: 4 })
    const times = store.bookTimes('keep')
    expect(times.shelf_id).toBe(times.shelf_position)
  })
})
