import 'fake-indexeddb/auto'
import { IDBFactory } from 'fake-indexeddb'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { rowKey, type SyncApi } from '../sync/api'
import type { Book, Quote } from '../sync/model'
import { LibraryStore } from '../sync/store'
import { duplicateGroups, missingCover, missingIsbn, missingPages } from './duplicates'
import { fillEmpty } from './fillEmpty'
import { setPlace } from '../physical/places'
import { mergeBooks, planMerge, type PhotoStorage } from './mergeBooks'

function book(over: Partial<Book> = {}): Book {
  return {
    id: '1', isbn: null, title: 'Blindness', authors: 'José Saramago', publisher: null, publishedYear: null,
    pageCount: null, coverUrl: null, description: null, status: 'TO_READ', rating: null, notes: '', dateAdded: 7,
    dateRead: null, dateStarted: null, currentPage: null, progressPercent: null, review: '', tags: [], series: null,
    seriesNumber: null, lentTo: null, lentOn: null, shelfId: null, shelfPosition: null, deleted: false, ...over,
  }
}

function quote(over: Partial<Quote> = {}): Quote {
  return { id: 'q1', bookId: '1', text: 'A line', page: null, dateAdded: 5, position: null, deleted: false, ...over }
}

const api: SyncApi = {
  pull: async (after) => ({ rows: [], last: after }),
  push: async (changes) => ({ applied: changes.map((c) => rowKey(c.table, c.id)), rejected: [] }),
  onPing: () => () => {},
}

const stores: LibraryStore[] = []
let clock = 1000

async function open() {
  clock = 1000
  const store = new LibraryStore({ api, owner: 'u1', retryDelays: [60_000], pollMs: 0, now: () => clock })
  stores.push(store)
  await store.load()
  return store
}

function fakeStorage(copyError: string | null = null, fail: { download?: boolean; upload?: boolean } = {}) {
  const copy = vi.fn(async () => ({ error: copyError ? { message: copyError } : null }))
  const remove = vi.fn()
  const download = vi.fn(async () =>
    fail.download ? { data: null, error: { message: 'no file' } } : { data: new Blob(['jpeg']), error: null },
  )
  const upload = vi.fn(async () => ({ error: fail.upload ? { message: 'denied' } : null }))
  const storage = { from: () => ({ copy, remove, download, upload }) } as unknown as PhotoStorage
  return { storage, copy, remove, download, upload }
}

beforeEach(() => {
  globalThis.indexedDB = new IDBFactory()
})

afterEach(async () => {
  await Promise.all(stores.splice(0).map((s) => s.close()))
})

describe('tidy lists', () => {
  it('listsBooksMissingData', () => {
    const books = [
      book({ id: 'a', coverUrl: null, pageCount: 100, isbn: '9780156007757' }),
      book({ id: 'b', coverUrl: 'photo', pageCount: null, isbn: '9780156007757' }),
      book({ id: 'c', coverUrl: 'https://x.example/c.jpg', pageCount: 0, isbn: null }),
      book({ id: 'd', coverUrl: '', pageCount: 5, isbn: '' }),
    ]
    expect(missingCover(books).map((b) => b.id)).toEqual(['a', 'd'])
    expect(missingPages(books).map((b) => b.id)).toEqual(['b', 'c'])
    expect(missingIsbn(books).map((b) => b.id)).toEqual(['c', 'd'])
  })

  it('findsDuplicatesByTitleAndAuthorOrIsbn', () => {
    const books = [
      book({ id: 'a', title: 'Ensaio sobre a Cegueira: romance', authors: 'José Saramago' }),
      book({ id: 'b', title: 'Ensaio Sobre a Cegueira', authors: 'Saramago José, Someone' }),
      book({ id: 'c', title: 'Other', authors: 'X', isbn: '9780156007757' }),
      book({ id: 'd', title: 'Something else', authors: 'Y', isbn: '9780156007757' }),
      book({ id: 'e', title: 'Ensaio sobre a Cegueira', authors: 'Another Person' }),
      book({ id: 'f', title: 'Lonely', authors: 'Z' }),
    ]
    const groups = duplicateGroups(books).map((g) => g.map((b) => b.id))
    expect(groups).toEqual([['a', 'b'], ['c', 'd']])
  })

  it('booksWithoutAnAuthorDontGroupByTitle', () => {
    const books = [
      book({ id: 'a', title: 'Poems', authors: '' }),
      book({ id: 'b', title: 'Poems', authors: ' , ' }),
      book({ id: 'c', title: 'Essays', authors: '', isbn: '9780156007757' }),
      book({ id: 'd', title: 'Other essays', authors: '', isbn: '9780156007757' }),
    ]
    expect(duplicateGroups(books).map((g) => g.map((b) => b.id))).toEqual([['c', 'd']])
  })

  it('aChainOfMatchesIsOneGroup', () => {
    const books = [
      book({ id: 'a', title: 'One', authors: 'P Q', isbn: '9780156007757' }),
      book({ id: 'b', title: 'Two', authors: 'R S', isbn: '9780156007757' }),
      book({ id: 'c', title: 'Two: sub', authors: 'S R', isbn: null }),
    ]
    expect(duplicateGroups(books).map((g) => g.map((b) => b.id))).toEqual([['a', 'b', 'c']])
  })
})

describe('fillEmpty', () => {
  it('lookupFillsOnlyEmptyFields', () => {
    const b = book({ publisher: 'Mine', pageCount: null, coverUrl: null, description: 'Mine too', publishedYear: null })
    const found = {
      isbn: '9780156007757', title: 'Different', authors: ['Other'], publisher: 'Theirs', year: 1995, pages: 300,
      coverUrl: 'https://c.example/x.jpg', description: 'Theirs',
    }
    expect(fillEmpty(b, found)).toEqual({
      isbn: '9780156007757', published_year: 1995, page_count: 300, cover_url: 'https://c.example/x.jpg',
    })
  })
})

describe('mergeBooks', () => {
  const addCase = (store: LibraryStore) =>
    store.setPref('bookcase.w', JSON.stringify({ v: 1, name: 'W', room: 'R', columns: [{ id: 'c', shelves: ['s1', 's2'] }] }))

  it('undoPutsThePlaceBackWhole', async () => {
    const store = await open()
    addCase(store)
    store.create(book({ id: 'keep', shelfId: 'gone', shelfPosition: 3 }))
    clock = 2000
    store.create(book({ id: 'drop', shelfId: 's1', shelfPosition: 3 }))
    clock = 3000
    const { undo } = await mergeBooks(store, store.book('keep') as Book, store.book('drop') as Book)
    expect(store.book('keep')).toMatchObject({ shelfId: 's1', shelfPosition: 3 })
    clock = 4000
    undo()
    expect(store.book('keep')).toMatchObject({ shelfId: 'gone', shelfPosition: 3 })
    expect(store.bookTimes('keep')).toMatchObject({ shelf_id: 4000, shelf_position: 4000 })
  })

  it('undoLeavesAPlaceChangedSinceTheMerge', async () => {
    const store = await open()
    addCase(store)
    store.create(book({ id: 'keep' }))
    clock = 2000
    store.create(book({ id: 'drop', shelfId: 's1', shelfPosition: 3 }))
    clock = 3000
    const { undo } = await mergeBooks(store, store.book('keep') as Book, store.book('drop') as Book)
    clock = 4000
    setPlace(store, 'keep', 's1', 9)
    clock = 5000
    undo()
    expect(store.book('keep')).toMatchObject({ shelfId: 's1', shelfPosition: 9 })
  })

  it('mergeKeepsNewestFieldsJoinsNotesMovesQuotesAndDeletesTheOther', async () => {
    const store = await open()
    store.create(book({ id: 'keep', publisher: 'Old House', notes: 'Kept note', tags: ['a'], pageCount: null }))
    clock = 2000
    store.create(
      book({ id: 'drop', publisher: 'New House', notes: 'Dropped note', tags: ['b'], pageCount: 250, isbn: '9780156007757' }),
    )
    store.createQuote(quote({ id: 'q1', bookId: 'drop', text: 'One' }))
    store.createQuote(quote({ id: 'q2', bookId: 'drop', text: 'Two', dateAdded: 6 }))
    clock = 3000

    const result = await mergeBooks(store, store.book('keep') as Book, store.book('drop') as Book)

    const kept = store.book('keep') as Book
    expect(kept.publisher).toBe('New House')
    expect(kept.pageCount).toBe(250)
    expect(kept.isbn).toBe('9780156007757')
    expect(kept.notes).toBe('Dropped note\n\nKept note')
    expect(kept.tags).toEqual(['a', 'b'])
    expect(store.quotes('keep').map((q) => q.text)).toEqual(['One', 'Two'])
    expect(store.book('drop')).toBeUndefined()
    expect(store.quotes('drop')).toEqual([])
    expect(result.coverKept).toBe(false)
  })

  it('mergingKeepsStatusAndDatesConsistent', async () => {
    const store = await open()
    // The kept book is To read; the other is Read with dates: the merged book keeps the kept status, so no read date.
    store.create(book({ id: 'drop', status: 'READ', dateRead: 900, dateStarted: 800 }))
    clock = 2000
    store.create(book({ id: 'keep', status: 'TO_READ' }))
    clock = 3000

    const plan = planMerge(store, store.book('keep') as Book, store.book('drop') as Book)
    expect(plan.merged).toMatchObject({ status: 'TO_READ', dateRead: null, dateStarted: null })
    await mergeBooks(store, store.book('keep') as Book, store.book('drop') as Book)

    expect(store.book('keep')).toMatchObject({ status: 'TO_READ', dateRead: null, dateStarted: null })
  })

  it('mergeMovesADropPhotoCoverToTheKeptBook', async () => {
    const store = await open()
    store.create(book({ id: 'keep', coverUrl: null }))
    clock = 2000
    store.create(book({ id: 'drop', coverUrl: 'photo' }))
    const before = store.bookTimes('keep').cover_url
    const { storage, copy, remove } = fakeStorage()
    clock = 3000

    const result = await mergeBooks(store, store.book('keep') as Book, store.book('drop') as Book, { owner: 'u1', storage })

    expect(copy).toHaveBeenCalledTimes(1)
    expect(copy).toHaveBeenCalledWith('u1/drop.jpg', 'u1/keep.jpg')
    expect(remove).not.toHaveBeenCalled()
    expect((store.book('keep') as Book).coverUrl).toBe('photo')
    expect(store.bookTimes('keep').cover_url).toBe(3000)
    expect(before).toBeLessThan(3000)
    expect(store.book('drop')).toBeUndefined()
    expect(result.coverKept).toBe(false)
  })

  it('mergingTwoPhotosTakesTheNewerOne', async () => {
    const store = await open()
    store.create(book({ id: 'keep', coverUrl: 'photo' }))
    clock = 2000
    store.create(book({ id: 'drop', coverUrl: 'photo' }))
    const { storage, copy, remove, download, upload } = fakeStorage()
    clock = 3000

    const plan = planMerge(store, store.book('keep') as Book, store.book('drop') as Book)
    expect(plan.photoFromDrop).toBe(true)
    expect(plan.replacesPhoto).toBe(true)
    const result = await mergeBooks(store, store.book('keep') as Book, store.book('drop') as Book, { owner: 'u1', storage })

    expect(download).toHaveBeenCalledWith('u1/drop.jpg')
    expect(upload).toHaveBeenCalledTimes(1)
    expect(upload.mock.calls[0]).toMatchObject(['u1/keep.jpg', expect.any(Blob), { upsert: true, contentType: 'image/jpeg' }])
    expect(copy).not.toHaveBeenCalled()
    expect(remove).not.toHaveBeenCalled()
    expect(store.bookTimes('keep').cover_url).toBe(3000)
    expect((store.book('keep') as Book).coverUrl).toBe('photo')
    expect(result.coverKept).toBe(false)
  })

  it('aFailedPhotoReplaceKeepsTheKeptPhoto', async () => {
    for (const fail of [{ download: true }, { upload: true }]) {
      const store = await open()
      store.create(book({ id: 'keep', coverUrl: 'photo' }))
      clock = 2000
      store.create(book({ id: 'drop', coverUrl: 'photo' }))
      const { storage, remove } = fakeStorage(null, fail)
      clock = 3000

      const result = await mergeBooks(store, store.book('keep') as Book, store.book('drop') as Book, { owner: 'u1', storage })

      expect(result.coverKept).toBe(true)
      expect(remove).not.toHaveBeenCalled()
      expect(store.bookTimes('keep').cover_url).toBe(1000)
      expect((store.book('keep') as Book).coverUrl).toBe('photo')
      expect(store.book('drop')).toBeUndefined()
    }
  })

  it('mergeKeepsItsOwnCoverWhenTheCopyFails', async () => {
    const store = await open()
    store.create(book({ id: 'keep', coverUrl: 'https://x.example/own.jpg' }))
    clock = 2000
    store.create(book({ id: 'drop', coverUrl: 'photo', publisher: 'House' }))
    const { storage, remove } = fakeStorage('boom')
    clock = 3000

    const result = await mergeBooks(store, store.book('keep') as Book, store.book('drop') as Book, { owner: 'u1', storage })

    const kept = store.book('keep') as Book
    expect(kept.coverUrl).toBe('https://x.example/own.jpg')
    expect(kept.publisher).toBe('House')
    expect(remove).not.toHaveBeenCalled()
    expect(result.coverKept).toBe(true)
    expect(store.book('drop')).toBeUndefined()
  })

  it('planMergeDescribesWithoutChangingAnything', async () => {
    const store = await open()
    store.create(book({ id: 'keep' }))
    clock = 2000
    store.create(book({ id: 'drop', pageCount: 90, coverUrl: 'photo' }))
    store.createQuote(quote({ id: 'q1', bookId: 'drop' }))

    const plan = planMerge(store, store.book('keep') as Book, store.book('drop') as Book)

    expect(plan.changes).toEqual(['pages', 'cover'])
    expect(plan.quotes).toBe(1)
    expect(plan.photoFromDrop).toBe(true)
    expect(plan.replacesPhoto).toBe(false)
    expect(plan.phoneData).toBe(false)
    expect((store.book('keep') as Book).pageCount).toBeNull()
    expect(store.book('drop')).toBeDefined()
  })
})
