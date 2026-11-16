import 'fake-indexeddb/auto'
import { IDBFactory } from 'fake-indexeddb'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { Found, Lookup } from '../add/lookup'
import { rowKey, type SyncApi } from '../sync/api'
import type { Book, SyncRow } from '../sync/model'
import { LibraryStore } from '../sync/store'
import type { PhotoStorage } from './mergeBooks'
import { ToastProvider } from '../ui/Toast'
import { TidyPage } from './TidyPage'

const T = 15_000

let pulled: SyncRow[] = []
/** Rows the phone changes after the first pull. */
let later: SyncRow[] = []
const api: SyncApi = {
  pull: async (after) => {
    if (after === 0) return { rows: pulled, last: pulled.length }
    const rows = later.splice(0)
    return { rows, last: after + rows.length }
  },
  push: async (changes) => ({ applied: changes.map((c) => rowKey(c.table, c.id)), rejected: [] }),
  onPing: () => () => {},
}

function book(over: Partial<Book> = {}): Book {
  return {
    id: '1', isbn: '9780156007757', title: 'Blindness', authors: 'José Saramago', publisher: null, publishedYear: null,
    pageCount: 300, coverUrl: 'https://x.example/c.jpg', description: null, status: 'TO_READ', rating: null, notes: '',
    dateAdded: 7, dateRead: null, dateStarted: null, currentPage: null, progressPercent: null, review: '', tags: [],
    series: null, seriesNumber: null, lentTo: null, lentOn: null, deleted: false, ...over,
  }
}

const stores: LibraryStore[] = []
async function open(...books: Book[]) {
  const store = new LibraryStore({ api, owner: 'u1', retryDelays: [60_000], pollMs: 0 })
  stores.push(store)
  await store.load()
  books.forEach((b) => store.create(b))
  return store
}

const storage = { from: () => ({ copy: vi.fn(), upload: vi.fn(), createSignedUrls: vi.fn() }) } as unknown as PhotoStorage

function fakeLookup(found: Found | null): Lookup {
  return { lookupIsbn: vi.fn(async () => found), searchTitle: vi.fn(async () => (found ? [found] : [])) }
}

beforeEach(() => {
  globalThis.indexedDB = new IDBFactory()
  pulled = []
  later = []
})
afterEach(async () => {
  await Promise.all(stores.splice(0).map((s) => s.close()))
})

describe('TidyPage', () => {
  it('showsTheListsWithCounts', async () => {
    const store = await open(book({ id: 'a', coverUrl: null }), book({ id: 'b', title: 'Other', authors: 'X', isbn: '9780140449136', pageCount: null }))
    render(<TidyPage store={store} owner="u1" storage={storage} />)

    expect(screen.getByRole('tab', { name: 'Missing cover (1)' })).toBeInTheDocument()
    expect(screen.getByRole('tab', { name: 'Missing pages (1)' })).toBeInTheDocument()
    expect(screen.getByRole('tab', { name: 'Missing ISBN (0)' })).toBeInTheDocument()
    expect(screen.getByRole('tab', { name: 'Possible duplicates (0)' })).toBeInTheDocument()
    expect(screen.getByText('Blindness')).toBeInTheDocument()
  }, T)

  it('lookUpFillsOnlyTheEmptyFields', async () => {
    const user = userEvent.setup()
    const store = await open(book({ id: 'a', coverUrl: null, publisher: 'Mine' }))
    const lookup = fakeLookup({
      isbn: '9780156007757', title: 'Blindness', authors: ['José Saramago'], publisher: 'Theirs', year: 1995,
      pages: 300, coverUrl: 'https://c.example/new.jpg', description: null,
    })
    render(<TidyPage store={store} owner="u1" storage={storage} lookup={lookup} />)

    await user.click(screen.getByRole('button', { name: 'Look up' }))

    await waitFor(() => expect((store.book('a') as Book).coverUrl).toBe('https://c.example/new.jpg'))
    expect((store.book('a') as Book).publisher).toBe('Mine')
    expect((store.book('a') as Book).publishedYear).toBe(1995)
  }, T)

  it('mergeAsksFirstAndShowsWhatWillHappen', async () => {
    const user = userEvent.setup()
    const store = await open(
      book({ id: 'a', title: 'Blindness' }),
      book({ id: 'b', title: 'Blindness: a novel', publisher: 'House' }),
    )
    store.createQuote({ id: 'q1', bookId: 'b', text: 'A line', page: null, dateAdded: 1, position: null, deleted: false })
    render(<TidyPage store={store} owner="u1" storage={storage} />)

    await user.click(screen.getByRole('tab', { name: 'Possible duplicates (1)' }))
    await user.click(screen.getByRole('button', { name: 'Merge' }))

    expect(
      screen.getByText(
        "You can undo the merge for a few seconds; bookmarks, e-books and a replaced cover photo won't come back.",
      ),
    ).toBeInTheDocument()
    expect(screen.getByText(/1 quote moves/i)).toBeInTheDocument()
    expect(store.book('b')).toBeDefined()

    await user.click(screen.getByRole('button', { name: 'Cancel' }))
    expect(store.book('b')).toBeDefined()

    await user.click(screen.getByRole('button', { name: 'Merge' }))
    await user.click(within(screen.getByRole('alertdialog')).getByRole('button', { name: 'Merge and delete' }))

    await waitFor(() => expect(store.book('b')).toBeUndefined())
    expect((store.book('a') as Book).publisher).toBe('House')
    expect(store.quotes('a')).toHaveLength(1)
  }, T)
})

describe('TidyPage merging, more', () => {
  it('mergePreviewWarnsAboutBookmarks', async () => {
    const user = userEvent.setup()
    pulled = [{ table: 'bookmarks', id: 'm1', fields: { book_id: 'b', deleted: false }, times: { book_id: 1 }, seq: 1 }]
    const store = await open(book({ id: 'a' }), book({ id: 'b', title: 'Blindness: a novel' }))
    render(<TidyPage store={store} owner="u1" storage={storage} />)

    await user.click(screen.getByRole('tab', { name: 'Possible duplicates (1)' }))
    await user.click(screen.getByRole('button', { name: 'Merge' }))

    expect(screen.getByText(/bookmarks and e-book on your phone are removed/i)).toBeInTheDocument()
  }, T)

  it('mergingAGroupOfThreeKeepsTheChosenOne', async () => {
    const user = userEvent.setup()
    const store = await open(
      book({ id: 'a', title: 'Blindness' }),
      book({ id: 'b', title: 'Blindness: one' }),
      book({ id: 'c', title: 'Blindness: two', publisher: 'House' }),
    )
    render(<TidyPage store={store} owner="u1" storage={storage} />)

    await user.click(screen.getByRole('tab', { name: 'Possible duplicates (1)' }))
    await user.click(screen.getByRole('radio', { name: /Blindness: one/ }))
    await user.click(screen.getByRole('button', { name: 'Merge' }))
    await user.click(screen.getByRole('button', { name: 'Merge and delete' }))

    await waitFor(() => expect(store.books().map((b) => b.id)).toEqual(['b']))
    expect((store.book('b') as Book).publisher).toBe('House')
  }, T)

  it('aBookCanBeLeftOutOfAMerge', async () => {
    const user = userEvent.setup()
    const store = await open(
      book({ id: 'a', title: 'Blindness' }),
      book({ id: 'b', title: 'Blindness: one', publisher: 'House' }),
      book({ id: 'c', title: 'Blindness: two' }),
    )
    render(<TidyPage store={store} owner="u1" storage={storage} />)
    await user.click(screen.getByRole('tab', { name: 'Possible duplicates (1)' }))

    // Leave "two" out: only the other two merge.
    await user.click(screen.getByRole('checkbox', { name: 'Not a duplicate: Blindness: two' }))
    await user.click(screen.getByRole('button', { name: 'Merge' }))
    expect(screen.getByRole('alertdialog')).toHaveTextContent('This will delete the other book.')
    await user.click(screen.getByRole('button', { name: 'Merge and delete' }))
    await waitFor(() => expect(store.books().map((b) => b.id).sort()).toEqual(['a', 'c']))
    expect((store.book('a') as Book).publisher).toBe('House')
  }, T)

  it('aGroupWithFewerThanTwoCheckedCannotMerge', async () => {
    const user = userEvent.setup()
    const store = await open(book({ id: 'a', title: 'Blindness' }), book({ id: 'b', title: 'Blindness: one' }))
    render(<TidyPage store={store} owner="u1" storage={storage} />)
    await user.click(screen.getByRole('tab', { name: 'Possible duplicates (1)' }))
    await user.click(screen.getByRole('checkbox', { name: 'Not a duplicate: Blindness: one' }))
    expect(screen.getByRole('button', { name: 'Merge' })).toBeDisabled()
    await user.click(screen.getByRole('checkbox', { name: 'Not a duplicate: Blindness: one' }))
    expect(screen.getByRole('button', { name: 'Merge' })).toBeEnabled()
  }, T)

  it('undoingAMergeRestoresBothBooks', async () => {
    const user = userEvent.setup()
    const keepBefore = book({ id: 'a', title: 'Blindness', publisher: null, rating: null })
    const dropBefore = book({ id: 'b', title: 'Blindness: a novel', publisher: 'House', rating: 4, tags: ['x'] })
    const store = await open(keepBefore, dropBefore)
    store.createQuote({ id: 'q1', bookId: 'b', text: 'A line', page: 2, dateAdded: 1, position: null, deleted: false })
    render(
      <ToastProvider>
        <TidyPage store={store} owner="u1" storage={storage} />
      </ToastProvider>,
    )
    await user.click(screen.getByRole('tab', { name: 'Possible duplicates (1)' }))
    await user.click(screen.getByRole('button', { name: 'Merge' }))
    await user.click(screen.getByRole('button', { name: 'Merge and delete' }))

    await waitFor(() => expect(store.book('b')).toBeUndefined())
    await waitFor(() => expect(screen.getByRole('status')).toHaveTextContent('Merged into Blindness.'))
    expect(store.book('a')).toMatchObject({ publisher: 'House', rating: 4 })
    expect(store.quotes('a')).toHaveLength(1)

    await user.click(screen.getByRole('button', { name: 'Undo' }))
    expect(store.book('a')).toEqual(keepBefore)
    expect(store.book('b')).toEqual(dropBefore)
    expect(store.quotes('a')).toEqual([])
    expect(store.quotes('b').map((q) => q.id)).toEqual(['q1'])
  }, T)

  it('undoingAMergeKeepsAFieldChangedSince', async () => {
    const user = userEvent.setup()
    // The first pull gives the cursor a start, so the next pull is a new one.
    pulled = [{ table: 'bookmarks', id: 'm0', fields: { book_id: 'zz', deleted: false }, times: { book_id: 1 }, seq: 1 }]
    const keepBefore = book({ id: 'a', title: 'Blindness', publisher: null, rating: null })
    const dropBefore = book({ id: 'b', title: 'Blindness: a novel', publisher: 'House', rating: 4 })
    const store = await open(keepBefore, dropBefore)
    render(
      <ToastProvider>
        <TidyPage store={store} owner="u1" storage={storage} />
      </ToastProvider>,
    )
    await user.click(screen.getByRole('tab', { name: 'Possible duplicates (1)' }))
    await user.click(screen.getByRole('button', { name: 'Merge' }))
    await user.click(screen.getByRole('button', { name: 'Merge and delete' }))
    await waitFor(() => expect(store.book('b')).toBeUndefined())
    await waitFor(() => expect(screen.getByRole('status')).toHaveTextContent('Merged into Blindness.'))
    expect(store.book('a')).toMatchObject({ publisher: 'House', rating: 4 })

    // The phone changes the score during the eight seconds.
    later.push({ table: 'books', id: 'a', fields: { rating: 2 }, times: { rating: Date.now() + 60_000 }, seq: 2 })
    await store.refresh()
    await user.click(screen.getByRole('button', { name: 'Undo' }))

    // What the merge filled in goes back; what changed since is left alone; the dropped book is back as it was.
    expect(store.book('a')).toMatchObject({ publisher: null, rating: 2 })
    expect(store.book('b')).toEqual(dropBefore)
  }, T)
})
