import 'fake-indexeddb/auto'
import { readFileSync } from 'node:fs'
import { zipSync, strToU8 } from 'fflate'
import { IDBFactory } from 'fake-indexeddb'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { CoverStorage } from '../covers/storage'
import type { ImageDecoder } from '../covers/coverUpload'
import { rowKey, type Change, type SyncApi } from '../sync/api'
import type { Book } from '../sync/model'
import { LibraryStore } from '../sync/store'
import { parseBackup } from './backup'
import { parseGoodreads } from './goodreads'
import { applyPlan, backupItems, goodreadsItems, planImport, previewText } from './importPlan'

const pushes: Change[][] = []
const api: SyncApi = {
  pull: async () => ({ rows: [], last: 0 }),
  push: async (changes) => {
    pushes.push(changes)
    return { applied: changes.map((c) => rowKey(c.table, c.id)), rejected: [] }
  },
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
  pushes.length = 0
})
afterEach(async () => {
  await Promise.all(stores.splice(0).map((s) => s.close()))
})

const NOW = 2_000_000_000_000
const csv = (rows: string[]) => ['Title,Author,ISBN13,Exclusive Shelf,My Rating,Private Notes,Publisher', ...rows].join('\n')

describe('planImport', () => {
  it('importMergesExistingBooks', async () => {
    // 2 of the 5 are already here: one by ISBN, one by title and first author (the ISBNs differ).
    const store = await open([
      book('dune', { title: 'Dune', authors: 'Frank Herbert', isbn: '9780441013593', notes: 'mine', publisher: 'Ace' }),
      book('emma', { title: 'Emma', authors: 'Jane Austen', isbn: null, rating: 4 }),
    ])
    const rows = parseGoodreads(csv([
      'Dune,Frank Herbert,="9780441013593",read,5,my note,Other Press',
      'Emma,Jane Austen,="9780140434262",to-read,0,,',
      'Ulysses,James Joyce,="9780141182803",to-read,0,,',
      'Hamlet,William Shakespeare,,to-read,0,,',
      'Dracula,Bram Stoker,,to-read,0,,',
    ]))

    const plan = planImport(goodreadsItems(rows, NOW), store, NOW)

    expect(previewText(plan)).toBe('3 new · 2 already in your library (merged) · 0 skipped')
    expect(store.books()).toHaveLength(2) // nothing is written by planning
    await applyPlan(plan, store, {})
    expect(store.books()).toHaveLength(5)
    const dune = store.book('dune') as Book
    // The notes are joined, the newer first; the filled publisher and the shelf's status and rating win.
    expect(dune.notes).toBe('my note\n\nmine')
    expect(dune).toMatchObject({ status: 'READ', rating: 5, publisher: 'Ace' })
    // Emma keeps her rating (Goodreads' empty one never erases), and the id.
    expect(store.book('emma')).toMatchObject({ rating: 4, status: 'TO_READ', isbn: '9780140434262' })
  })

  it('countsWhatCannotBeImportedAsSkipped', async () => {
    const store = await open()
    const rows = parseGoodreads(readFileSync('test/fixtures/goodreads_library_export.csv', 'utf8'))
    const plan = planImport(goodreadsItems(rows, NOW), store, NOW)
    // The row with no title is skipped.
    expect(previewText(plan)).toBe('4 new · 0 already in your library (merged) · 1 skipped')

    const dup = parseGoodreads(csv([
      'A,X,="9780441013593",read,0,,', 'B,Y,="9780441013593",read,0,,', // the same ISBN again
      'C,Z,,read,0,,', 'C,Z,,read,0,,', // the same title and author again
    ]))
    expect(previewText(planImport(goodreadsItems(dup, NOW), store, NOW))).toBe(
      '2 new · 0 already in your library (merged) · 2 skipped',
    )
  })

  it('keepsTheNewestFieldWhenAnOlderBackupIsRestored', async () => {
    const store = await open([book('a', { isbn: '9780441013593', title: 'Dune', authors: 'Frank Herbert' })])
    store.edit('books', 'a', { publisher: 'Edited today' }) // stamped now
    const backup = parseBackup(strToU8(JSON.stringify({
      version: 1, exportedAt: 1_000,
      books: [{ isbn: '9780441013593', title: 'Dune', authors: 'Frank Herbert', publisher: 'From the backup', pageCount: 688 }],
    })))
    const plan = planImport(backupItems(backup), store, NOW)
    await applyPlan(plan, store, {})
    // The edit is newer than the backup; the page count was empty here.
    expect(store.book('a')).toMatchObject({ publisher: 'Edited today', pageCount: 688 })
  })

  it('restoresQuotesOnceAndGoals', async () => {
    const store = await open([book('a', { isbn: '9780441013593', title: 'Dune', authors: 'Frank Herbert' })])
    store.createQuote({ id: 'q0', bookId: 'a', text: 'Fear is the mind-killer.', page: 1, dateAdded: 1, position: null, deleted: false })
    store.edit('challenges', '2025', { goal: 10 })
    const backup = parseBackup(readFileSync('test/fixtures/backup.json'))

    const plan = planImport(backupItems(backup), store, NOW)
    expect(plan.quoteCount).toBe(1) // the other one is already there
    await applyPlan(plan, store, {})

    expect(store.quotes('a').map((q) => q.text)).toEqual(['Fear is the mind-killer.', 'The spice must flow.'])
    expect(store.goal(2025)).toBe(24)
    expect(store.books().map((b) => b.title).sort()).toEqual(['Dune', 'Good Omens'])
  })

  it('sendsChangesInBatchesOfAHundred', async () => {
    const store = await open()
    const rows = Array.from({ length: 250 }, (_, i) => `Title ${String(i)},Author ${String(i)},,to-read,0,,`)
    await applyPlan(planImport(goodreadsItems(parseGoodreads(csv(rows)), NOW), store, NOW), store, {})
    await store.idle()
    expect(pushes.flat()).toHaveLength(250)
    expect(Math.max(...pushes.map((p) => p.length))).toBeLessThanOrEqual(100)
  })
})

describe('backup covers', () => {
  const decode: ImageDecoder = async () => ({
    width: 10, height: 10, encode: async () => new Blob(['jpeg'], { type: 'image/jpeg' }),
  })

  it('backupZipRestoresCovers', async () => {
    const upload = vi.fn(async () => ({ error: null }))
    const storage: CoverStorage = { from: () => ({ upload, createSignedUrl: vi.fn() }) }
    const store = await open([book('have', { isbn: '9780000000002', title: 'Covered', authors: 'C', coverUrl: 'https://x/y.jpg' })])
    const library = {
      version: 1, exportedAt: 1,
      books: [
        { title: 'Dune', authors: 'Frank Herbert', coverUrl: 'cover:photo-dune.jpg' },
        { title: 'Emma', authors: 'Jane Austen', coverUrl: 'cover:photo-emma.jpg' },
        { title: 'Lost', authors: 'Nobody', coverUrl: 'cover:missing.jpg' },
        { title: 'Covered', authors: 'C', isbn: '9780000000002', coverUrl: 'cover:photo-covered.jpg' },
      ],
    }
    const backup = parseBackup(zipSync({
      'library.json': strToU8(JSON.stringify(library)),
      'covers/photo-dune.jpg': new Uint8Array([1]), 'covers/photo-emma.jpg': new Uint8Array([2]),
      'covers/photo-covered.jpg': new Uint8Array([3]),
    }))

    const plan = planImport(backupItems(backup), store, NOW)
    const result = await applyPlan(plan, store, { owner: 'u1', storage, decode })

    const byTitle = (t: string) => store.books().find((b) => b.title === t) as Book
    // Each photo is uploaded under its book's id, and the book is marked "photo".
    expect(upload.mock.calls.map((c) => (c as unknown as [string])[0]).sort()).toEqual(
      [`u1/${byTitle('Dune').id}.jpg`, `u1/${byTitle('Emma').id}.jpg`].sort(),
    )
    expect(byTitle('Dune').coverUrl).toBe('photo')
    expect(byTitle('Emma').coverUrl).toBe('photo')
    // A photo missing from the file leaves no cover; a book that has a cover keeps it.
    expect(byTitle('Lost').coverUrl).toBeNull()
    expect(byTitle('Covered').coverUrl).toBe('https://x/y.jpg')
    expect(result.coverFailures).toBe(0)
  })

  it('countsAPhotoThatCouldNotBeUploaded', async () => {
    const storage: CoverStorage = {
      from: () => ({ upload: async () => ({ error: { message: 'full' } }), createSignedUrl: vi.fn() }),
    }
    const store = await open()
    const backup = parseBackup(zipSync({
      'library.json': strToU8(JSON.stringify({ version: 1, books: [{ title: 'Dune', authors: 'F', coverUrl: 'cover:a.jpg' }] })),
      'covers/a.jpg': new Uint8Array([1]),
    }))
    const result = await applyPlan(planImport(backupItems(backup), store, NOW), store, { owner: 'u1', storage, decode })
    expect(result.coverFailures).toBe(1)
    expect(store.books()).toHaveLength(1) // the book is still imported
  })
})
