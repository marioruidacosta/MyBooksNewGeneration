import 'fake-indexeddb/auto'
import { IDBFactory } from 'fake-indexeddb'
import { readFileSync } from 'node:fs'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { strFromU8, strToU8, unzipSync, zipSync } from 'fflate'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { CoverStorage } from '../covers/storage'
import type { ImageDecoder } from '../covers/coverUpload'
import { rowKey, type SyncApi } from '../sync/api'
import type { Book } from '../sync/model'
import { LibraryStore } from '../sync/store'
import { blobBytes } from './blobBytes'
import { ImportPage } from './ImportPage'

const api: SyncApi = {
  pull: async () => ({ rows: [], last: 0 }),
  push: async (changes) => ({ applied: changes.map((c) => rowKey(c.table, c.id)), rejected: [] }),
  onPing: () => () => {},
}

const book = (id: string, over: Partial<Book> = {}): Book => ({
  id, isbn: null, title: `Book ${id}`, authors: 'Author', publisher: null, publishedYear: null, pageCount: null,
  coverUrl: null, description: null, status: 'TO_READ', rating: null, notes: '', dateAdded: 7, dateRead: null,
  dateStarted: null, currentPage: null, progressPercent: null, review: '', tags: [], series: null, seriesNumber: null,
  lentTo: null, lentOn: null, shelfId: null, shelfPosition: null, deleted: false, ...over,
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

const FIVE = [
  'Title,Author,ISBN13,Exclusive Shelf,My Rating,Private Notes',
  'Dune,Frank Herbert,="9780441013593",read,5,from goodreads',
  'Emma,Jane Austen,="9780140434262",to-read,0,',
  'Ulysses,James Joyce,="9780141182803",to-read,0,',
  'Hamlet,William Shakespeare,,to-read,0,',
  'Dracula,Bram Stoker,,to-read,0,',
].join('\n')

const file = (text: string, name = 'goodreads_library_export.csv') => new File([text], name, { type: 'text/csv' })

describe('ImportPage', () => {
  it('importMergesExistingBooks', async () => {
    const user = userEvent.setup({ delay: null })
    const store = await open([
      book('dune', { title: 'Dune', authors: 'Frank Herbert', isbn: '9780441013593', notes: 'my own' }),
      book('emma', { title: 'Emma', authors: 'Jane Austen', isbn: null }),
    ])
    render(<ImportPage store={store} now={() => Date.now() + 10_000} />)

    await user.upload(screen.getByLabelText('Goodreads or MyBooks file'), file(FIVE))

    expect(await screen.findByText('3 new · 2 already in your library (merged) · 0 skipped')).toBeInTheDocument()
    // Nothing is written until the person confirms.
    expect(store.books()).toHaveLength(2)
    await user.click(screen.getByRole('button', { name: 'Cancel' }))
    expect(screen.queryByText(/already in your library/)).not.toBeInTheDocument()
    expect(store.books()).toHaveLength(2)

    await user.upload(screen.getByLabelText('Goodreads or MyBooks file'), file(FIVE))
    await user.click(await screen.findByRole('button', { name: 'Import' }))

    expect(await screen.findByText('Imported 3 new books and merged 2.')).toBeInTheDocument()
    expect(store.books()).toHaveLength(5)
    expect(store.book('dune')?.notes).toBe('from goodreads\n\nmy own')
  })

  it('showsWhatItSkips', async () => {
    const user = userEvent.setup({ delay: null })
    const store = await open()
    render(<ImportPage store={store} />)
    await user.upload(
      screen.getByLabelText('Goodreads or MyBooks file'),
      new File([readFileSync('test/fixtures/goodreads_library_export.csv')], 'export.csv'),
    )
    expect(await screen.findByText('4 new · 0 already in your library (merged) · 1 skipped')).toBeInTheDocument()
  })

  it('previewsABackupWithItsQuotesAndGoals', async () => {
    const user = userEvent.setup({ delay: null })
    const store = await open()
    render(<ImportPage store={store} />)
    await user.upload(
      screen.getByLabelText('Goodreads or MyBooks file'),
      new File([readFileSync('test/fixtures/backup.json')], 'library.json'),
    )
    expect(await screen.findByText('2 new · 0 already in your library (merged) · 0 skipped')).toBeInTheDocument()
    expect(screen.getByText('Also 2 quotes and 1 reading goal.')).toBeInTheDocument()
  })

  it('saysWhenTheFileIsNotOne', async () => {
    const user = userEvent.setup({ delay: null })
    const store = await open()
    render(<ImportPage store={store} />)
    await user.upload(screen.getByLabelText('Goodreads or MyBooks file'), file('name,age\nAna,3\n', 'x.csv'))
    expect(await screen.findByRole('alert')).toHaveTextContent("That isn't a Goodreads export.")
    await user.upload(screen.getByLabelText('Goodreads or MyBooks file'), file('{"version":2}', 'x.json'))
    expect(await screen.findByRole('alert')).toHaveTextContent('Unsupported backup version 2')
    expect(screen.queryByRole('button', { name: 'Import' })).not.toBeInTheDocument()
  })

  it('exportsGoodreadsAndTheBackup', async () => {
    const user = userEvent.setup({ delay: null })
    const store = await open([
      book('a', { title: 'Dune', authors: 'Frank Herbert', coverUrl: 'photo' }),
      book('b', { title: 'Emma', authors: 'Jane Austen' }),
    ])
    store.edit('challenges', '2026', { goal: 30 })
    const download = vi.fn()
    const getPhoto = vi.fn(async (id: string) => (id === 'a' ? new Uint8Array([4, 5]) : null))
    render(<ImportPage store={store} download={download} getPhoto={getPhoto} now={() => new Date(2026, 10, 16, 12).getTime()} />)

    await user.click(screen.getByRole('button', { name: 'Export for Goodreads' }))
    expect(download).toHaveBeenCalledTimes(1)
    const [csvName, csvBlob] = download.mock.calls[0] as [string, Blob]
    expect(csvName).toBe('mybooks-goodreads.csv')
    const csv = strFromU8(await blobBytes(csvBlob))
    expect(csv.split('\r\n')[0]).toContain('Title,Author,Additional Authors,ISBN,ISBN13')
    expect(csv).toContain('Dune')

    await user.click(screen.getByRole('button', { name: 'Export a backup' }))
    await waitFor(() => expect(download).toHaveBeenCalledTimes(2))
    const [zipName, zipBlob] = download.mock.calls[1] as [string, Blob]
    expect(zipName).toBe('mybooks-backup-2026-11-16.zip')
    const entries = unzipSync(await blobBytes(zipBlob))
    expect(Object.keys(entries).sort()).toEqual(['covers/a.jpg', 'library.json'])
    expect(getPhoto).toHaveBeenCalledWith('a')
    expect(JSON.parse(strFromU8(entries['library.json'])).challenges).toEqual([{ year: 2026, goal: 30 }])
  })

  it('importShowsPhotoUploadProgress', async () => {
    const user = userEvent.setup({ delay: null })
    const store = await open()
    const gates: (() => void)[] = []
    const upload = vi.fn(() => new Promise<{ error: null }>((resolve) => gates.push(() => resolve({ error: null }))))
    const storage: CoverStorage = { from: () => ({ upload, createSignedUrls: vi.fn() }) }
    const decode: ImageDecoder = async () => ({
      width: 10, height: 10, encode: async () => new Blob(['jpeg'], { type: 'image/jpeg' }),
    })
    const library = {
      version: 1, exportedAt: 1,
      books: [
        { title: 'Dune', authors: 'Frank Herbert', coverUrl: 'cover:a.jpg' },
        { title: 'Emma', authors: 'Jane Austen', coverUrl: 'cover:b.jpg' },
      ],
    }
    const zip = zipSync({
      'library.json': strToU8(JSON.stringify(library)), 'covers/a.jpg': new Uint8Array([1]), 'covers/b.jpg': new Uint8Array([2]),
    })
    render(<ImportPage store={store} owner="u1" storage={storage} decode={decode} />)
    await user.upload(screen.getByLabelText('Goodreads or MyBooks file'), new File([zip], 'backup.zip'))
    await user.click(await screen.findByRole('button', { name: 'Import' }))

    expect(await screen.findByText('Uploading photos 1 of 2…')).toBeInTheDocument()
    gates[0]()
    expect(await screen.findByText('Uploading photos 2 of 2…')).toBeInTheDocument()
    await waitFor(() => expect(gates).toHaveLength(2))
    gates[1]()
    expect(await screen.findByText('Imported 2 new books.')).toBeInTheDocument()
    expect(screen.queryByText(/Uploading photos/)).not.toBeInTheDocument()
  })

  it('aChangeOnlyInQuotesIsShownBeforeImport', async () => {
    const user = userEvent.setup({ delay: null })
    const store = await open([book('dune', { title: 'Dune', authors: 'Frank Herbert', isbn: '9780441013593' })])
    const backup = {
      version: 1, exportedAt: 1,
      books: [{ title: 'Dune', authors: 'Frank Herbert', isbn: '9780441013593', quotes: [{ text: 'Fear is the mind-killer', page: 3, dateAdded: 5 }] }],
    }
    render(<ImportPage store={store} now={() => Date.now() + 10_000} />)
    await user.upload(screen.getByLabelText('Goodreads or MyBooks file'), new File([JSON.stringify(backup)], 'backup.json'))
    expect(await screen.findByText('Also 1 quote.')).toBeInTheDocument()

    // A sync brings the same quote in: the counts line is the same, the "Also" line is not.
    store.createQuote({ id: 'q1', bookId: 'dune', text: 'Fear is the mind-killer', page: 3, dateAdded: 5, position: null, deleted: false })
    await user.click(screen.getByRole('button', { name: 'Import' }))

    expect(screen.getByRole('alert')).toHaveTextContent('Your library changed.')
    expect(screen.queryByText('Also 1 quote.')).not.toBeInTheDocument()
    expect(store.quotes('dune')).toHaveLength(1)
    expect(screen.queryByText(/^Imported/)).not.toBeInTheDocument()
  })

  it('aChangedLibraryIsPlannedAgainBeforeImport', async () => {
    const user = userEvent.setup({ delay: null })
    const store = await open()
    render(<ImportPage store={store} now={() => Date.now() + 10_000} />)
    await user.upload(screen.getByLabelText('Goodreads or MyBooks file'), file(FIVE))
    expect(await screen.findByText('5 new · 0 already in your library (merged) · 0 skipped')).toBeInTheDocument()

    // A sync brings one of the books in while the preview is open.
    store.create(book('dune', { title: 'Dune', authors: 'Frank Herbert', isbn: '9780441013593' }))
    await user.click(screen.getByRole('button', { name: 'Import' }))

    // Nothing was written from the old counts: the new ones are shown to confirm.
    expect(await screen.findByText('4 new · 1 already in your library (merged) · 0 skipped')).toBeInTheDocument()
    expect(screen.getByRole('alert')).toHaveTextContent('Your library changed. Check the new counts, then import.')
    expect(store.books()).toHaveLength(1)

    await user.click(screen.getByRole('button', { name: 'Import' }))
    expect(await screen.findByText('Imported 4 new books and merged 1.')).toBeInTheDocument()
    expect(store.books()).toHaveLength(5)
  })
})
