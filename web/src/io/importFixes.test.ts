import 'fake-indexeddb/auto'
import { zipSync, strToU8 } from 'fflate'
import { IDBFactory } from 'fake-indexeddb'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { CoverStorage } from '../covers/storage'
import type { ImageDecoder } from '../covers/coverUpload'
import { rowKey, type SyncApi } from '../sync/api'
import type { Book } from '../sync/model'
import { bookToFields } from '../sync/codec'
import { LibraryStore } from '../sync/store'
import { parseBackup } from './backup'
import { parseGoodreads } from './goodreads'
import { applyPlan, backupItems, goodreadsItems, planImport, previewText } from './importPlan'

const api: SyncApi = {
  pull: async () => ({ rows: [], last: 0 }),
  push: async (changes) => ({ applied: changes.map((c) => rowKey(c.table, c.id)), rejected: [] }),
  onPing: () => () => {},
}

const book = (id: string, over: Partial<Book> = {}): Book => ({
  id, isbn: null, title: `Book ${id}`, authors: 'Author', publisher: null, publishedYear: null, pageCount: null,
  coverUrl: null, description: null, status: 'TO_READ', rating: null, notes: '', dateAdded: 7, dateRead: null,
  dateStarted: null, currentPage: null, progressPercent: null, review: '', tags: [], series: null, seriesNumber: null,
  lentTo: null, lentOn: null, deleted: false, ...over,
})

const stores: LibraryStore[] = []
async function open(books: Book[] = []) {
  const store = new LibraryStore({ api, owner: 'u1', retryDelays: [60_000], pollMs: 0 })
  stores.push(store)
  await store.load()
  books.forEach((b) => store.create(b))
  return store
}

beforeEach(() => {
  globalThis.indexedDB = new IDBFactory()
})
afterEach(async () => {
  await Promise.all(stores.splice(0).map((s) => s.close()))
})

const NOW = 2_000_000_000_000
const csv = (rows: string[]) => ['Title,Author,ISBN13,Exclusive Shelf,My Rating,Private Notes,Publisher', ...rows].join('\n')

describe('matching like the app importers', () => {
  it('twoIsbnsAreTwoBooksOnImport', async () => {
    const store = await open([
      book('d1', { title: 'The Dark Tower: The Gunslinger', authors: 'Stephen King', isbn: '9780441013593' }),
    ])
    const rows = parseGoodreads(csv([
      'The Dark Tower: The Drawing of the Three,Stephen King,="9780141182803",to-read,0,,',
    ]))
    expect(previewText(planImport(goodreadsItems(rows, NOW), store, NOW))).toBe(
      '1 new · 0 already in your library (merged) · 0 skipped',
    )
    // With no ISBN on the file's side, the same title and author is the same book.
    const bare = parseGoodreads(csv(['The Dark Tower: The Drawing of the Three,Stephen King,,to-read,0,,']))
    expect(previewText(planImport(goodreadsItems(bare, NOW), store, NOW))).toBe(
      '0 new · 1 already in your library (merged) · 0 skipped',
    )
  })

  it('twoRowsForOneLibraryBookMergeOnce', async () => {
    const store = await open([book('a', { title: 'Dune', authors: 'Frank Herbert', notes: 'mine' })])
    const rows = parseGoodreads(csv(['Dune,Frank Herbert,,read,5,first,', 'Dune,Frank Herbert,,read,5,second,']))
    const plan = planImport(goodreadsItems(rows, NOW), store, NOW)
    expect(previewText(plan)).toBe('0 new · 1 already in your library (merged) · 0 skipped')
    await applyPlan(plan, store, {})
    expect(store.books()).toHaveLength(1)
    expect(store.book('a')?.notes.split('\n\n').sort()).toEqual(['first', 'mine', 'second'])
  })

  it('planningIsFastForBigLibraries', async () => {
    const store = await open(
      Array.from({ length: 3000 }, (_, i) => book(`b${String(i)}`, { title: `Title ${String(i)}`, authors: `Author ${String(i)}` })),
    )
    const rows = Array.from({ length: 3000 }, (_, i) =>
      book(`n${String(i)}`, { title: `Title ${String(i + 1500)}`, authors: `Author ${String(i + 1500)}` }))
    // Counts the work, not the seconds (a wall-clock budget fails on a busy machine): every read of a library book. An
    // index reads each book a few times; a plan that scanned the library for each row would read 3000 x 3000.
    let reads = 0
    const counted = store.books().map((b) => new Proxy(b, { get: (target, key, receiver) => { reads++; return Reflect.get(target, key, receiver) } }))
    const plan = planImport(goodreadsItems(rows, NOW), { books: () => counted, bookTimes: (id) => store.bookTimes(id), quotes: (id) => store.quotes(id), goal: (y) => store.goal(y) }, NOW)
    expect(reads).toBeLessThan(100 * 3000) // about 106 000 when linear
    expect(previewText(plan)).toBe('1500 new · 1500 already in your library (merged) · 0 skipped')
  }, 60_000)
})

describe('covers on a merge', () => {
  const decode: ImageDecoder = async () => ({
    width: 10, height: 10, encode: async () => new Blob(['jpeg'], { type: 'image/jpeg' }),
  })
  const zipWith = (coverUrl: string) => parseBackup(zipSync({
    'library.json': strToU8(JSON.stringify({
      version: 1, exportedAt: NOW + 1_000_000,
      books: [{ isbn: '9780441013593', title: 'Dune', authors: 'F', coverUrl }],
    })),
    'covers/p.jpg': new Uint8Array([1]),
  }))

  it('mergeKeepsAnExistingCoverOverABackupPhoto', async () => {
    const upload = vi.fn(async () => ({ error: null }))
    const storage: CoverStorage = { from: () => ({ upload, createSignedUrls: vi.fn() }) }
    const store = await open([book('a', { isbn: '9780441013593', title: 'Dune', authors: 'F', coverUrl: 'https://x/y.jpg' })])
    const plan = planImport(backupItems(zipWith('cover:p.jpg')), store, NOW)
    await applyPlan(plan, store, { owner: 'u1', storage, decode })
    expect(store.book('a')?.coverUrl).toBe('https://x/y.jpg')
    expect(upload).not.toHaveBeenCalled()
  })

  it('aFailedPhotoUploadLeavesNoMarker', async () => {
    const storage: CoverStorage = {
      from: () => ({ upload: async () => ({ error: { message: 'full' } }), createSignedUrls: vi.fn() }),
    }
    const store = await open([book('a', { isbn: '9780441013593', title: 'Dune', authors: 'F' })])
    const plan = planImport(backupItems(zipWith('cover:p.jpg')), store, NOW)
    const result = await applyPlan(plan, store, { owner: 'u1', storage, decode })
    expect(result.coverFailures).toBe(1)
    expect(store.book('a')?.coverUrl).toBeNull()
  })
})

describe('status on import', () => {
  it('anOldGoodreadsExportDoesNotUnreadABook', async () => {
    const store = await open([
      book('a', { title: 'Dune', authors: 'Frank Herbert', status: 'READ', dateRead: 123, rating: 4 }),
      book('b', { title: 'Emma', authors: 'Jane Austen', status: 'READING', dateStarted: 5, currentPage: 10 }),
    ])
    const rows = parseGoodreads(csv(['Dune,Frank Herbert,,to-read,0,,', 'Emma,Jane Austen,,read,0,,']))
    await applyPlan(planImport(goodreadsItems(rows, NOW), store, NOW), store, {})
    expect(store.book('a')).toMatchObject({ status: 'READ', dateRead: 123, rating: 4 })
    // Other changes still apply.
    expect(store.book('b')?.status).toBe('READ')
  })

  it('aBackupReadBookWithoutADateStaysWithout', async () => {
    const store = await open()
    const backup = parseBackup(strToU8(JSON.stringify({
      version: 1, exportedAt: 1, books: [{ title: 'Dune', authors: 'F', status: 'READ' }, { title: 'Now', authors: 'G', status: 'READING' }],
    })))
    await applyPlan(planImport(backupItems(backup), store, NOW), store, {})
    expect(store.books().find((b) => b.title === 'Dune')?.dateRead).toBeNull()
    expect(store.books().find((b) => b.title === 'Now')?.dateStarted).toBeNull()
  })
})

describe('field limits', () => {
  it('importNeverWritesAnOverLimitField', async () => {
    const store = await open([book('a', { title: 'Dune', authors: 'Frank Herbert', notes: 'n'.repeat(15_000) })])
    const long = (n: number) => 'z'.repeat(n)
    const rows = parseGoodreads(csv([
      `${long(400)},${long(400)},="9780141182803",read,0,${long(25_000)},`,
      'Dune,Frank Herbert,,read,5,' + long(9000) + ',',
    ]))
    const items = goodreadsItems(rows, NOW)
    items.items.forEach((i) => Object.assign(i.book, { description: long(30_000), coverUrl: 'https://x.example/' + long(600) }))
    const plan = planImport(items, store, NOW)
    await applyPlan(plan, store, {})
    const limits: Record<string, number> = {
      title: 300, authors: 300, description: 20_000, notes: 20_000, review: 2000, cover_url: 500, tags: 2000,
    }
    const check = (fields: Record<string, unknown>) => {
      for (const [f, max] of Object.entries(limits)) {
        const v = fields[f]
        if (typeof v === 'string') expect(v.length, f).toBeLessThanOrEqual(max)
      }
    }
    expect(store.books().length).toBe(2)
    for (const b of store.books()) check(bookToFields(b))
    for (const m of plan.merged) check(m.changes)
  })
})

describe('batch B import', () => {
  const decode: ImageDecoder = async () => ({
    width: 10, height: 10, encode: async () => new Blob(['jpeg'], { type: 'image/jpeg' }),
  })

  it('aLaterUrlCoverCancelsAQueuedPhoto', async () => {
    const upload = vi.fn(async () => ({ error: null }))
    const storage: CoverStorage = { from: () => ({ upload, createSignedUrls: vi.fn() }) }
    const store = await open([book('a', { title: 'Dune', authors: 'Frank Herbert' })])
    const rows = (...covers: string[]) =>
      parseBackup(zipSync({
        'library.json': strToU8(JSON.stringify({
          version: 1, exportedAt: 1, books: covers.map((coverUrl) => ({ title: 'Dune', authors: 'Frank Herbert', coverUrl })),
        })),
        'covers/p.jpg': new Uint8Array([1]),
      }))

    // A photo first, a web cover later: the web cover wins, and the photo is not uploaded over it.
    const plan = planImport(backupItems(rows('cover:p.jpg', 'https://x.test/c.jpg')), store, NOW)
    expect(plan.uploads).toHaveLength(0)
    await applyPlan(plan, store, { owner: 'u1', storage, decode })
    expect(upload).not.toHaveBeenCalled()
    expect(store.book('a')?.coverUrl).toBe('https://x.test/c.jpg')

    // The other order, and a photo alone, still upload.
    globalThis.indexedDB = new IDBFactory()
    const other = await open([book('b', { title: 'Dune', authors: 'Frank Herbert' })])
    expect(planImport(backupItems(rows('https://x.test/c.jpg', 'cover:p.jpg')), other, NOW).uploads).toHaveLength(0)
    globalThis.indexedDB = new IDBFactory()
    const alone = await open([book('c', { title: 'Dune', authors: 'Frank Herbert' })])
    expect(planImport(backupItems(rows('cover:p.jpg')), alone, NOW).uploads).toHaveLength(1)
  })
})
