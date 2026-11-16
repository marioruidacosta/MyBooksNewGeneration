import 'fake-indexeddb/auto'
import { IDBFactory } from 'fake-indexeddb'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { rowKey, type SyncApi } from '../sync/api'
import type { Book } from '../sync/model'
import { LibraryStore } from '../sync/store'
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

describe('export progress', () => {
  it('exportReportsPhotosItCouldNotInclude', async () => {
    const user = userEvent.setup()
    const store = await open([
      book('a', { coverUrl: 'photo' }), book('b', { coverUrl: 'photo' }), book('c', { coverUrl: 'photo' }),
    ])
    const getPhoto = vi.fn(async (id: string) => {
      if (id === 'b') throw new Error('network')
      return id === 'a' ? new Uint8Array([1]) : null
    })
    render(<ImportPage store={store} download={vi.fn()} getPhoto={getPhoto} />)
    await user.click(screen.getByRole('button', { name: 'Export a backup' }))
    expect(await screen.findByText('2 photos could not be included.')).toBeInTheDocument()
  })

  it('exportButtonsAreDisabledWhileRunning', async () => {
    const user = userEvent.setup()
    const store = await open([book('a', { coverUrl: 'photo' })])
    let release: (v: Uint8Array) => void = () => {}
    const getPhoto = () => new Promise<Uint8Array | null>((r) => (release = r))
    const download = vi.fn()
    render(<ImportPage store={store} download={download} getPhoto={getPhoto} />)
    await user.click(screen.getByRole('button', { name: 'Export a backup' }))
    expect(screen.getByRole('button', { name: 'Export a backup' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Export for Goodreads' })).toBeDisabled()
    expect(screen.getByText('Preparing the export…')).toBeInTheDocument()
    release(new Uint8Array([1]))
    await waitFor(() => expect(download).toHaveBeenCalled())
    await waitFor(() => expect(screen.getByRole('button', { name: 'Export a backup' })).toBeEnabled())
  })
})
