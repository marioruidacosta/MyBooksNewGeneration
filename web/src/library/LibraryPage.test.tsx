import 'fake-indexeddb/auto'
import { IDBFactory } from 'fake-indexeddb'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { rowKey, SyncError, type SyncApi } from '../sync/api'
import type { Book, SyncRow } from '../sync/model'
import { mergeFields } from '../sync/merge'
import { LibraryStore } from '../sync/store'
import { CoverContext } from '../covers/storage'
import { ToastProvider } from '../ui/Toast'
import { COLUMN_STORAGE_KEY, VIEW_STORAGE_KEY } from './columns'
import { quoteOfTheDay } from '../quotes/daily'
import { clearMedia, setPhone } from '../test-media'
import { LibraryPage } from './LibraryPage'

const T = 10_000

/** A server that takes everything and has nothing else. */
const api: SyncApi = {
  pull: async () => ({ rows: [], last: 0 }),
  push: async (changes) => ({ applied: changes.map((c) => rowKey(c.table, c.id)), rejected: [] }),
  onPing: () => () => {},
}

function book(id: string, over: Partial<Book> = {}): Book {
  return {
    id, isbn: null, title: `Book ${id}`, authors: 'Author', publisher: null, publishedYear: null, pageCount: null,
    coverUrl: null, description: null, status: 'TO_READ', rating: null, notes: '', dateAdded: 7, dateRead: null,
    dateStarted: null, currentPage: null, progressPercent: null, review: '', tags: [], series: null,
    seriesNumber: null, lentTo: null, lentOn: null, deleted: false, ...over,
  }
}

const NOW = 1_800_000_000_000
const stores: LibraryStore[] = []

/** What "the phone" has: rows served at the first pull, and rows served at the next one (an edit made meanwhile). */
const phone = { served: false, first: [] as SyncRow[], later: [] as SyncRow[] }
const phoneApi: SyncApi = {
  ...api,
  pull: async (after) => {
    if (!phone.served) {
      phone.served = true
      return { rows: phone.first, last: phone.first.length }
    }
    const rows = phone.later.splice(0)
    return { rows, last: after + rows.length }
  },
}

async function openWithPhone(books: Book[]) {
  const store = new LibraryStore({ api: phoneApi, owner: 'u1', retryDelays: [60_000], pollMs: 0 })
  stores.push(store)
  await store.load()
  books.forEach((b) => store.create(b))
  return store
}

async function open(books: Book[]) {
  const store = new LibraryStore({ api, owner: 'u1', retryDelays: [60_000], pollMs: 0 })
  stores.push(store)
  await store.load()
  books.forEach((b) => store.create(b))
  return store
}

function show(store: LibraryStore, extra: { onAdd?: () => void; onOpen?: (id: string) => void } = {}) {
  const onAdd = extra.onAdd ?? vi.fn()
  const onOpen = extra.onOpen ?? vi.fn()
  const page = () => (
    <ToastProvider>
      <LibraryPage store={store} year={2026} onAdd={onAdd} onOpen={onOpen} />
    </ToastProvider>
  )
  const view = render(page())
  return { onAdd, onOpen, unmount: view.unmount, again: () => render(page()) }
}

/** The titles in the table, in row order. */
const titles = () =>
  within(screen.getByRole('table')).queryAllByRole('link').map((a) => a.textContent)

/** The same as `titles`, without the accessibility-tree walk of a role query: for a test that reads it dozens of times. */
const quickTitles = () => [...document.querySelectorAll('table a')].map((a) => a.textContent)

beforeEach(() => {
  globalThis.indexedDB = new IDBFactory()
  window.localStorage.clear()
  phone.served = false
  phone.first = []
  phone.later = []
})

afterEach(async () => {
  clearMedia()
  vi.restoreAllMocks()
  await Promise.all(stores.splice(0).map((s) => s.close()))
})

describe('LibraryPage', () => {
  it('searchIgnoresAccents', async () => {
    const store = await open([
      book('1', { title: 'Ensaio sobre a Cegueira', authors: 'José Saramago', isbn: '9789722015981' }),
      book('2', { title: 'Another', authors: 'Someone Else' }),
    ])
    const user = userEvent.setup({ delay: null })
    show(store)

    await user.type(screen.getByRole('searchbox', { name: 'Search' }), 'JOSE')
    expect(titles()).toEqual(['Ensaio sobre a Cegueira'])

    await user.clear(screen.getByRole('searchbox', { name: 'Search' }))
    await user.type(screen.getByRole('searchbox', { name: 'Search' }), 'cegueira saramago')
    expect(titles()).toEqual(['Ensaio sobre a Cegueira'])

    await user.clear(screen.getByRole('searchbox', { name: 'Search' }))
    await user.type(screen.getByRole('searchbox', { name: 'Search' }), '9789722015981')
    expect(titles()).toEqual(['Ensaio sobre a Cegueira'])

    await user.clear(screen.getByRole('searchbox', { name: 'Search' }))
    await user.type(screen.getByRole('searchbox', { name: 'Search' }), 'nothing like it')
    expect(titles()).toEqual([])
  }, T)

  it('sortsByEveryColumn', async () => {
    const store = await open([
      book('a', {
        title: 'Alpha', authors: 'Zed', status: 'READ', rating: 2, tags: ['zeta'], series: 'Saga', seriesNumber: '2',
        dateStarted: 300, dateRead: 100, publisher: 'Zeta', publishedYear: 2001, pageCount: 100, lentTo: null,
        dateAdded: 30, coverUrl: 'https://example.com/a.jpg',
      }),
      book('b', {
        title: 'Bravo', authors: 'Anna', status: 'READING', rating: null, tags: [], series: 'Saga', seriesNumber: '10',
        dateStarted: 100, dateRead: null, publisher: 'Alfa', publishedYear: 1999, pageCount: 300, lentTo: 'Zoe',
        dateAdded: 10, coverUrl: null,
      }),
      book('c', {
        title: 'Charlie', authors: 'Mia', status: 'WISHLIST', rating: 5, tags: ['alpha'], series: null,
        seriesNumber: null, dateStarted: null, dateRead: 50, publisher: 'Mid', publishedYear: 2010, pageCount: 200,
        lentTo: 'Ann', dateAdded: 20, coverUrl: 'https://example.com/c.jpg',
      }),
    ])
    const everything = Object.fromEntries(
      ['cover', 'title', 'authors', 'status', 'score', 'tags', 'series', 'started', 'read', 'publisher', 'year',
        'pages', 'lentTo', 'added'].map((id) => [id, true]),
    )
    window.localStorage.setItem(COLUMN_STORAGE_KEY, JSON.stringify(everything))
    const user = userEvent.setup({ delay: null })
    show(store)

    // Newest first until a column is chosen.
    expect(quickTitles()).toEqual(['Alpha', 'Charlie', 'Bravo'])

    const name = { a: 'Alpha', b: 'Bravo', c: 'Charlie' }
    const order = (ids: string) => ids.split('').map((i) => name[i as 'a'])
    const cases: [header: string, asc: string, desc: string][] = [
      ['Cover', 'acb', 'bac'],
      ['Title', 'abc', 'cba'],
      ['Authors', 'bca', 'acb'],
      ['Status', 'bac', 'cab'], // Reading, Read, To buy
      ['Score', 'acb', 'cab'], // no score always last
      ['Tags', 'cab', 'acb'], // no tags always last
      ['Series #', 'abc', 'bac'], // 2 before 10; no series last
      ['Started', 'bac', 'abc'], // never started last
      ['Read', 'cab', 'acb'], // not read last
      ['Publisher', 'bca', 'acb'],
      ['Year', 'bac', 'cab'],
      ['Pages', 'acb', 'bca'],
      ['Lent to', 'cba', 'bca'], // not lent last
      ['Added', 'bca', 'acb'],
    ]
    for (const [header, asc, desc] of cases) {
      const button = [...document.querySelectorAll('th button')].find((b) => b.textContent === header) as HTMLElement
      expect(button, header).toBeDefined()
      await user.click(button)
      expect(quickTitles(), `${header} ascending`).toEqual(order(asc))
      expect(button.closest('th')).toHaveAttribute('aria-sort', 'ascending')
      await user.click(button)
      expect(quickTitles(), `${header} descending`).toEqual(order(desc))
      expect(button.closest('th')).toHaveAttribute('aria-sort', 'descending')
      await user.click(button)
      expect(quickTitles(), `${header} unsorted`).toEqual(['Alpha', 'Charlie', 'Bravo'])
    }
  }, T)

  it('filtersByStatusTagAndLending', async () => {
    const store = await open([
      book('a', { title: 'Alpha', status: 'READING', tags: ['sf'], dateAdded: 3 }),
      book('b', { title: 'Bravo', status: 'READ', tags: ['sf', 'old'], lentTo: 'Ana', dateAdded: 2 }),
      book('c', { title: 'Charlie', status: 'READ', tags: ['old'], dateAdded: 1 }),
    ])
    const user = userEvent.setup({ delay: null })
    show(store)

    await user.selectOptions(screen.getByLabelText('Filter by status'), 'READ')
    expect(titles()).toEqual(['Bravo', 'Charlie'])
    await user.selectOptions(screen.getByLabelText('Filter by tag'), 'sf')
    expect(titles()).toEqual(['Bravo'])
    await user.selectOptions(screen.getByLabelText('Filter by status'), '')
    expect(titles()).toEqual(['Alpha', 'Bravo'])
    await user.selectOptions(screen.getByLabelText('Filter by tag'), '')
    await user.selectOptions(screen.getByLabelText('Filter by lending'), 'out')
    expect(titles()).toEqual(['Bravo'])
    await user.selectOptions(screen.getByLabelText('Filter by lending'), 'in')
    expect(titles()).toEqual(['Alpha', 'Charlie'])
  }, T)

  it('readingNowShowsTheBooksBeingReadWithHowFarAlong', async () => {
    const store = await open([
      book('a', { title: 'Alpha', status: 'READING', progressPercent: 53, dateStarted: 1 }),
      book('b', { title: 'Bravo', status: 'READING', currentPage: 50, pageCount: 200, dateStarted: 2 }),
      book('c', { title: 'Charlie', status: 'READ' }),
    ])
    const onOpen = vi.fn()
    show(store, { onOpen })
    const shelf = screen.getByRole('region', { name: 'Reading now' })
    // Most recently started first; only the books being read.
    expect(within(shelf).getAllByRole('link').map((a) => a.getAttribute('aria-label'))).toEqual(['Bravo', 'Alpha'])
    expect(within(shelf).getByRole('progressbar', { name: 'Alpha progress' })).toHaveAttribute('aria-valuenow', '53')
    // Without a percentage, the pages say how far along it is.
    expect(within(shelf).getByRole('progressbar', { name: 'Bravo progress' })).toHaveAttribute('aria-valuenow', '25')
    await userEvent.setup({ delay: null }).click(within(shelf).getByRole('link', { name: 'Alpha' }))
    expect(onOpen).toHaveBeenCalledWith('a')
  }, T)

  it('noShelfWhenNothingIsBeingRead', async () => {
    const store = await open([book('c', { status: 'READ' })])
    show(store)
    expect(screen.queryByRole('region', { name: 'Reading now' })).not.toBeInTheDocument()
  }, T)

  it('countsTheBooksByStatus', async () => {
    const store = await open([
      book('a', { status: 'READING' }), book('b', { status: 'READ' }), book('c', { status: 'READ' }),
    ])
    show(store)
    const counts = screen.getByLabelText('Counts')
    expect(counts).toHaveTextContent('3 books')
    expect(counts).toHaveTextContent('1 reading')
    expect(counts).toHaveTextContent('2 read')
    expect(counts).toHaveTextContent('0 to buy')
  }, T)

  it('bulkSetStatusEditsEachSelectedBook', async () => {
    const store = await open([
      book('a', { title: 'Alpha', dateAdded: 3 }),
      book('b', { title: 'Bravo', dateAdded: 2 }),
      book('c', { title: 'Charlie', dateAdded: 1 }),
    ])
    const edit = vi.spyOn(store, 'edit')
    vi.spyOn(Date, 'now').mockReturnValue(NOW)
    const user = userEvent.setup({ delay: null })
    show(store)

    await user.click(screen.getByRole('checkbox', { name: 'Select Alpha' }))
    await user.click(screen.getByRole('checkbox', { name: 'Select Bravo' }))
    expect(screen.getByText('2 selected')).toBeInTheDocument()
    await user.selectOptions(screen.getByLabelText('Set status'), 'READING')

    // The book page's rule: Reading starts now.
    expect(edit.mock.calls).toEqual([
      // Tagged, so that a change made to many books at once doesn't flood the community with new posts.
      ['books', 'a', { status: 'READING', date_started: NOW }, { origin: 'bulk' }],
      ['books', 'b', { status: 'READING', date_started: NOW }, { origin: 'bulk' }],
    ])
    expect(store.book('a')?.status).toBe('READING')
    expect(store.book('b')?.status).toBe('READING')
    expect(store.book('c')?.status).toBe('TO_READ')

    // Again to the same status: nothing to change, nothing stamped.
    edit.mockClear()
    await user.selectOptions(screen.getByLabelText('Set status'), 'READING')
    expect(edit).not.toHaveBeenCalled()
  }, T)

  it('bulkMarkingReadSetsTheReadDate', async () => {
    const store = await open([
      book('a', { title: 'Alpha', dateAdded: 3 }),
      book('b', { title: 'Bravo', status: 'READING', dateStarted: 500, currentPage: 40, progressPercent: 20, dateAdded: 2 }),
    ])
    vi.spyOn(Date, 'now').mockReturnValue(NOW)
    const user = userEvent.setup({ delay: null })
    show(store)

    await user.click(screen.getByRole('checkbox', { name: 'Select Alpha' }))
    await user.click(screen.getByRole('checkbox', { name: 'Select Bravo' }))
    await user.selectOptions(screen.getByLabelText('Set status'), 'READ')
    expect(store.book('a')).toMatchObject({ status: 'READ', dateRead: NOW, dateStarted: null })
    expect(store.book('b')).toMatchObject({ status: 'READ', dateRead: NOW, dateStarted: 500 })

    // Back to To read: the dates and the progress go, as on the book page.
    await user.selectOptions(screen.getByLabelText('Set status'), 'TO_READ')
    expect(store.book('b')).toMatchObject({
      status: 'TO_READ', dateRead: null, dateStarted: null, currentPage: null, progressPercent: null,
    })
    expect(store.book('a')).toMatchObject({ status: 'TO_READ', dateRead: null })
  }, T)

  it('bulkAddTagKeepsExistingTags', async () => {
    const store = await open([
      book('a', { title: 'Alpha', tags: ['x'], dateAdded: 3 }),
      book('b', { title: 'Bravo', tags: ['x', 'new'], dateAdded: 2 }),
      book('c', { title: 'Charlie', dateAdded: 1 }),
    ])
    const edit = vi.spyOn(store, 'edit')
    const user = userEvent.setup({ delay: null })
    show(store)

    for (const t of ['Alpha', 'Bravo', 'Charlie']) await user.click(screen.getByRole('checkbox', { name: `Select ${t}` }))
    await user.type(screen.getByLabelText('Tag'), 'new')
    await user.click(screen.getByRole('button', { name: 'Add tag' }))

    expect(store.book('a')?.tags).toEqual(['x', 'new'])
    expect(store.book('b')?.tags).toEqual(['x', 'new'])
    expect(store.book('c')?.tags).toEqual(['new'])
    // Bravo had it already: not touched.
    expect(edit.mock.calls).toEqual([
      ['books', 'a', { tags: ['x', 'new'] }, { origin: 'bulk' }],
      ['books', 'c', { tags: ['new'] }, { origin: 'bulk' }],
    ])

    edit.mockClear()
    await user.click(screen.getByRole('button', { name: 'Remove tag' }))
    expect(store.book('a')?.tags).toEqual(['x'])
    expect(store.book('b')?.tags).toEqual(['x'])
    expect(store.book('c')?.tags).toEqual([])
    expect(edit).toHaveBeenCalledTimes(3)
  }, T)

  it('aNumberWithoutASeriesIsIgnored', async () => {
    const store = await open([book('a', { title: 'Alpha', series: 'Saga', seriesNumber: '2' })])
    const user = userEvent.setup({ delay: null })
    show(store)
    await user.click(screen.getByRole('checkbox', { name: 'Select Alpha' }))
    await user.type(screen.getByLabelText('Series number'), '7')
    await user.click(screen.getByRole('button', { name: 'Set series' }))
    expect(store.book('a')?.series).toBeNull()
    expect(store.book('a')?.seriesNumber).toBeNull()
  }, T)

  it('bulkSeriesAndLending', async () => {
    const store = await open([
      book('a', { title: 'Alpha', dateAdded: 3, lentTo: 'Ana', lentOn: 5 }),
      book('b', { title: 'Bravo', dateAdded: 2 }),
    ])
    const edit = vi.spyOn(store, 'edit')
    const user = userEvent.setup({ delay: null })
    show(store)
    await user.click(screen.getByRole('checkbox', { name: 'Select Alpha' }))
    await user.click(screen.getByRole('checkbox', { name: 'Select Bravo' }))

    await user.type(screen.getByLabelText('Series name'), 'Saga')
    await user.click(screen.getByRole('button', { name: 'Set series' }))
    expect(store.book('a')?.series).toBe('Saga')
    expect(store.book('b')?.series).toBe('Saga')

    // Alpha is lent to Ana already: only Bravo changes, and Alpha's date stays.
    edit.mockClear()
    await user.type(screen.getByLabelText('Lend to'), 'Ana')
    await user.click(screen.getByRole('button', { name: 'Lend' }))
    expect(edit).toHaveBeenCalledTimes(1)
    expect(edit.mock.calls[0].slice(0, 2)).toEqual(['books', 'b'])
    expect(store.book('a')?.lentOn).toBe(5)
    expect(store.book('b')?.lentTo).toBe('Ana')
    expect(store.book('b')?.lentOn).not.toBeNull()

    await user.click(screen.getByRole('button', { name: 'Mark returned' }))
    expect(store.book('a')?.lentTo).toBeNull()
    expect(store.book('a')?.lentOn).toBeNull()
    expect(store.book('b')?.lentTo).toBeNull()
  }, T)

  it('deletingShowsUndoAndUndoRestoresTheBooks', async () => {
    const original = [
      book('a', { title: 'Alpha', dateAdded: 3, status: 'READING', rating: 4, tags: ['x', 'y'], currentPage: 5, pageCount: 90, review: 'Fine' }),
      book('b', { title: 'Bravo', dateAdded: 2, series: 'Saga', seriesNumber: '2', lentTo: 'Zoe', lentOn: 9 }),
      book('c', { title: 'Charlie', dateAdded: 1 }),
    ]
    const store = await open(original)
    const confirm = vi.spyOn(window, 'confirm')
    const user = userEvent.setup({ delay: null })
    show(store)

    await user.click(screen.getByRole('checkbox', { name: 'Select Alpha' }))
    await user.click(screen.getByRole('checkbox', { name: 'Select Bravo' }))
    await user.click(screen.getByRole('button', { name: 'Delete' }))

    // No question: the books go, and the toast offers to bring them back.
    expect(confirm).not.toHaveBeenCalled()
    expect(store.books().map((b) => b.id)).toEqual(['c'])
    expect(titles()).toEqual(['Charlie'])
    expect(screen.queryByText('2 selected')).not.toBeInTheDocument()
    expect(screen.getByRole('status')).toHaveTextContent('Deleted 2 books.')

    await user.click(screen.getByRole('button', { name: 'Undo' }))
    expect(store.books().map((b) => b.id).sort()).toEqual(['a', 'b', 'c'])
    expect(store.book('a')).toEqual(original[0])
    expect(store.book('b')).toEqual(original[1])
    expect(titles()).toEqual(['Alpha', 'Bravo', 'Charlie'])
    expect(screen.queryByRole('button', { name: 'Undo' })).not.toBeInTheDocument()
  }, T)

  it('theDeleteKeyDeletesToo', async () => {
    const store = await open([book('a', { title: 'Alpha', dateAdded: 3 }), book('b', { title: 'Bravo', dateAdded: 2 })])
    const confirm = vi.spyOn(window, 'confirm')
    const user = userEvent.setup({ delay: null })
    show(store)
    await user.click(screen.getByRole('checkbox', { name: 'Select Alpha' }))
    await user.keyboard('{Delete}')
    expect(confirm).not.toHaveBeenCalled()
    expect(store.books().map((b) => b.id)).toEqual(['b'])
    expect(screen.getByRole('status')).toHaveTextContent('Deleted 1 book.')
  }, T)

  it('undoRestoresQuotesToo', async () => {
    const store = await open([book('a', { title: 'Alpha' })])
    store.createQuote({ id: 'q1', bookId: 'a', text: 'One', page: 3, dateAdded: 1, position: 'p', deleted: false })
    store.createQuote({ id: 'q2', bookId: 'a', text: 'Two', page: null, dateAdded: 2, position: null, deleted: false })
    const before = store.quotes('a')
    const user = userEvent.setup({ delay: null })
    show(store)
    await user.click(screen.getByRole('checkbox', { name: 'Select Alpha' }))
    await user.click(screen.getByRole('button', { name: 'Delete' }))
    expect(store.quotes('a')).toEqual([])
    await user.click(screen.getByRole('button', { name: 'Undo' }))
    expect(store.quotes('a')).toEqual(before)
  }, T)

  it('aRestoredBookSyncsToThePhone', async () => {
    const pushed: { table: string; id: string; fields: Record<string, unknown> }[] = []
    const recording: SyncApi = {
      ...api,
      push: async (changes) => {
        pushed.push(...changes.map((c) => ({ table: c.table, id: c.id, fields: c.fields })))
        return { applied: changes.map((c) => rowKey(c.table, c.id)), rejected: [] }
      },
    }
    const store = new LibraryStore({ api: recording, owner: 'u1', retryDelays: [60_000], pollMs: 0 })
    stores.push(store)
    await store.load()
    store.create(book('a', { title: 'Alpha' }))
    store.createQuote({ id: 'q1', bookId: 'a', text: 'One', page: null, dateAdded: 1, position: null, deleted: false })
    await store.idle()

    const user = userEvent.setup({ delay: null })
    show(store)
    await user.click(screen.getByRole('checkbox', { name: 'Select Alpha' }))
    await user.click(screen.getByRole('button', { name: 'Delete' }))
    await store.idle()
    pushed.length = 0
    await user.click(screen.getByRole('button', { name: 'Undo' }))
    await store.idle()

    const row = pushed.filter((c) => c.table === 'books' && c.id === 'a').pop()
    expect(row?.fields).toMatchObject({ title: 'Alpha', deleted: false })
    const quote = pushed.filter((c) => c.table === 'quotes' && c.id === 'q1').pop()
    expect(quote?.fields).toMatchObject({ text: 'One', book_id: 'a', deleted: false })
  }, T)

  it('holdingDeleteDeletesOnce', async () => {
    const store = await open([book('a', { title: 'Alpha', dateAdded: 3 }), book('b', { title: 'Bravo', dateAdded: 2 })])
    const user = userEvent.setup({ delay: null })
    show(store)
    await user.click(screen.getByRole('checkbox', { name: 'Select Alpha' }))

    fireEvent.keyDown(document.body, { key: 'Delete' })
    // The key held down repeats: those are not new presses.
    fireEvent.keyDown(document.body, { key: 'Delete', repeat: true })
    fireEvent.keyDown(document.body, { key: 'Delete', repeat: true })
    expect(store.books().map((b) => b.id)).toEqual(['b'])
    expect(screen.getByRole('status')).toHaveTextContent('Deleted 1 book.')
  }, T)

  it('goalIsEditable', async () => {
    const store = await open([
      book('a', { status: 'READ', dateRead: new Date(2026, 2, 1).getTime() }),
      book('b', { status: 'READ', dateRead: new Date(2025, 2, 1).getTime() }),
    ])
    const edit = vi.spyOn(store, 'edit')
    const user = userEvent.setup({ delay: null })
    show(store)

    const goal = screen.getByRole('spinbutton', { name: '2026 goal' })
    expect(goal).toHaveValue(null)
    expect(screen.getByText('1 read in 2026')).toBeInTheDocument()
    await user.type(goal, '30{Enter}')

    expect(edit).toHaveBeenCalledWith('challenges', '2026', { goal: 30 })
    expect(store.goal(2026)).toBe(30)
    expect(screen.getByRole('spinbutton', { name: '2026 goal' })).toHaveValue(30)

    // Leaving it as it is writes nothing; a bad value goes back.
    edit.mockClear()
    await user.click(goal)
    await user.tab()
    expect(edit).not.toHaveBeenCalled()
    await user.clear(goal)
    await user.tab()
    expect(edit).not.toHaveBeenCalled()
    expect(screen.getByRole('spinbutton', { name: '2026 goal' })).toHaveValue(30)
  }, T)

  it('keysFocusSearchAddABookAndOpenOne', async () => {
    const store = await open([book('a', { title: 'Alpha' })])
    const user = userEvent.setup({ delay: null })
    const { onAdd, onOpen } = show(store)

    await user.keyboard('/')
    expect(screen.getByRole('searchbox', { name: 'Search' })).toHaveFocus()
    // Typed into the search, the keys are letters.
    await user.keyboard('n')
    expect(onAdd).not.toHaveBeenCalled()
    expect(screen.getByRole('searchbox', { name: 'Search' })).toHaveValue('n')

    await user.clear(screen.getByRole('searchbox', { name: 'Search' }))
    await user.click(document.body)
    await user.keyboard('n')
    expect(onAdd).toHaveBeenCalledTimes(1)

    await user.click(screen.getByRole('link', { name: 'Alpha' }))
    expect(onOpen).toHaveBeenCalledWith('a')
  }, T)

  it('remembersTheVisibleColumns', async () => {
    const store = await open([book('a', { title: 'Alpha', publisher: 'Pub House' })])
    const user = userEvent.setup({ delay: null })
    show(store)

    expect(screen.queryByText('Pub House')).not.toBeInTheDocument()
    await user.click(screen.getByText('Columns'))
    await user.click(screen.getByRole('checkbox', { name: 'Publisher' }))
    expect(screen.getByText('Pub House')).toBeInTheDocument()
    expect(JSON.parse(window.localStorage.getItem(COLUMN_STORAGE_KEY) ?? '{}').publisher).toBe(true)
  }, T)
  it('aFirstVisitOfflineSaysSo', async () => {
    const offline: SyncApi = {
      ...api,
      pull: async () => {
        throw new SyncError('offline', 'Failed to fetch')
      },
    }
    const store = new LibraryStore({ api: offline, owner: 'u1', retryDelays: [60_000], pollMs: 0 })
    stores.push(store)
    await store.load()
    show(store)
    expect(screen.getByText("You're offline. Your library will appear once you're connected.")).toBeInTheDocument()
    expect(screen.queryByText(/No books yet/)).not.toBeInTheDocument()
  }, T)

  it('anEmptyLibraryOnlineStillSaysNoBooksYet', async () => {
    const store = await open([])
    show(store)
    expect(screen.getByText(/No books yet/)).toBeInTheDocument()
    expect(screen.getByText(/Choose Add/)).toBeInTheDocument()
    expect(screen.queryByText(/Press n/)).not.toBeInTheDocument()
    expect(screen.queryByText(/You're offline/)).not.toBeInTheDocument()
  }, T)
  it('photoCoversAreSignedInOneCall', async () => {
    const store = await open([
      book('1', { coverUrl: 'photo' }),
      book('2', { coverUrl: 'photo' }),
      book('3', { coverUrl: 'photo' }),
      book('4', { coverUrl: 'https://example.com/c.jpg' }),
    ])
    const createSignedUrls = vi.fn(async (paths: string[]) => ({
      data: paths.map((path) => ({ path, signedUrl: `https://signed.example/${path}`, error: null })),
      error: null,
    }))
    const covers = { owner: 'u-batch', storage: { from: () => ({ upload: vi.fn(), createSignedUrls }) } }
    render(
      <CoverContext.Provider value={covers}>
        <LibraryPage store={store} year={2026} onAdd={vi.fn()} onOpen={vi.fn()} />
      </CoverContext.Provider>,
    )
    await waitFor(() => expect(document.querySelectorAll('img[src^="https://signed.example/"]')).toHaveLength(3))
    // One request for every photo on the page, not one per book.
    expect(createSignedUrls).toHaveBeenCalledTimes(1)
    expect([...createSignedUrls.mock.calls[0][0]].sort()).toEqual(['u-batch/1.jpg', 'u-batch/2.jpg', 'u-batch/3.jpg'])
  }, T)

  it('clearingASeriesClearsItsNumbers', async () => {
    const store = await open([
      book('a', { title: 'Alpha', dateAdded: 3, series: 'Saga', seriesNumber: '2' }),
      book('b', { title: 'Bravo', dateAdded: 2, series: 'Saga', seriesNumber: '10' }),
    ])
    const user = userEvent.setup({ delay: null })
    show(store)
    await user.click(screen.getByRole('checkbox', { name: 'Select Alpha' }))
    await user.click(screen.getByRole('checkbox', { name: 'Select Bravo' }))
    await user.click(screen.getByRole('button', { name: 'Set series' }))
    for (const id of ['a', 'b']) {
      expect(store.book(id)?.series).toBeNull()
      expect(store.book(id)?.seriesNumber).toBeNull()
    }
  }, T)

  it('shelfProgressSavesTheCurrentPage', async () => {
    const store = await open([
      book('a', { title: 'Alpha', status: 'READING', dateStarted: 1, pageCount: 200, currentPage: 50, progressPercent: 25 }),
    ])
    const onOpen = vi.fn()
    const user = userEvent.setup({ delay: null })
    show(store, { onOpen })
    const shelf = screen.getByRole('region', { name: 'Reading now' })

    await user.click(within(shelf).getByRole('button', { name: 'Update progress for Alpha' }))
    const input = within(shelf).getByRole('spinbutton', { name: 'Current page of Alpha' })
    expect(input).toHaveValue(50)
    expect(shelf).toHaveTextContent('of 200')
    await user.clear(input)
    await user.type(input, '120{Enter}')

    // As the app's quick progress: the page, and the percentage that follows from the page count.
    expect(store.book('a')).toMatchObject({ currentPage: 120, progressPercent: 60, status: 'READING' })
    expect(within(shelf).queryByRole('spinbutton')).not.toBeInTheDocument()
    expect(within(shelf).getByRole('progressbar', { name: 'Alpha progress' })).toHaveAttribute('aria-valuenow', '60')

    // Leaving the field saves too; a page past the end caps the percentage at 100 and leaves the status alone.
    await user.click(within(shelf).getByRole('button', { name: 'Update progress for Alpha' }))
    const again = within(shelf).getByRole('spinbutton', { name: 'Current page of Alpha' })
    await user.clear(again)
    await user.type(again, '230')
    await user.tab()
    expect(store.book('a')).toMatchObject({ currentPage: 230, progressPercent: 100, status: 'READING' })

    // The cover still opens the book.
    await user.click(within(shelf).getByRole('link', { name: 'Alpha' }))
    expect(onOpen).toHaveBeenCalledWith('a')
  }, T)

  it('shelfProgressWithoutPagesSavesAPercent', async () => {
    const store = await open([
      book('a', { title: 'Alpha', status: 'READING', dateStarted: 1, pageCount: null, currentPage: 7, progressPercent: null }),
    ])
    const user = userEvent.setup({ delay: null })
    show(store)
    const shelf = screen.getByRole('region', { name: 'Reading now' })

    await user.click(within(shelf).getByRole('button', { name: 'Update progress for Alpha' }))
    const input = within(shelf).getByRole('spinbutton', { name: 'Percent read of Alpha' })
    await user.type(input, '40{Enter}')
    expect(store.book('a')).toMatchObject({ currentPage: null, progressPercent: 40 })

    // Something that is not 0 to 100 is not saved.
    await user.click(within(shelf).getByRole('button', { name: 'Update progress for Alpha' }))
    const bad = within(shelf).getByRole('spinbutton', { name: 'Percent read of Alpha' })
    await user.clear(bad)
    await user.type(bad, '140{Enter}')
    expect(store.book('a')?.progressPercent).toBe(40)
    expect(bad).toHaveAttribute('aria-invalid', 'true')
    expect(within(shelf).getByRole('alert')).toHaveTextContent('Type a percentage from 0 to 100')
  }, T)

  it('escapeLeavesProgressUnchanged', async () => {
    const store = await open([
      book('a', { title: 'Alpha', status: 'READING', dateStarted: 1, pageCount: 200, currentPage: 50, progressPercent: 25 }),
    ])
    const user = userEvent.setup({ delay: null })
    show(store)
    const shelf = screen.getByRole('region', { name: 'Reading now' })
    await user.click(within(shelf).getByRole('button', { name: 'Update progress for Alpha' }))
    const input = within(shelf).getByRole('spinbutton', { name: 'Current page of Alpha' })
    await user.clear(input)
    await user.type(input, '99{Escape}')
    expect(store.book('a')).toMatchObject({ currentPage: 50, progressPercent: 25 })
    expect(within(shelf).queryByRole('spinbutton')).not.toBeInTheDocument()
  }, T)

  it('closingTheShelfEditorReturnsFocus', async () => {
    const store = await open([
      book('a', { title: 'Alpha', status: 'READING', dateStarted: 1, pageCount: 200, currentPage: 50, progressPercent: 25 }),
    ])
    const user = userEvent.setup({ delay: null })
    show(store)
    const shelf = screen.getByRole('region', { name: 'Reading now' })

    await user.click(within(shelf).getByRole('button', { name: 'Update progress for Alpha' }))
    await user.keyboard('60{Enter}')
    expect(within(shelf).queryByRole('spinbutton')).not.toBeInTheDocument()
    expect(within(shelf).getByRole('button', { name: 'Update progress for Alpha' })).toHaveFocus()

    await user.click(within(shelf).getByRole('button', { name: 'Update progress for Alpha' }))
    await user.keyboard('{Escape}')
    expect(within(shelf).getByRole('button', { name: 'Update progress for Alpha' })).toHaveFocus()

    // Leaving the field for somewhere else keeps focus there.
    await user.click(within(shelf).getByRole('button', { name: 'Update progress for Alpha' }))
    await user.click(screen.getByRole('button', { name: 'Add a book' }))
    expect(within(shelf).getByRole('button', { name: 'Update progress for Alpha' })).not.toHaveFocus()
  }, T)

  it('changingStatusInTheTableAppliesTheDates', async () => {
    const store = await open([
      book('a', { title: 'Alpha', status: 'TO_READ', currentPage: null }),
      book('b', { title: 'Bravo', status: 'READING', dateStarted: 5, currentPage: 3, progressPercent: 10, dateAdded: 6 }),
    ])
    const user = userEvent.setup({ delay: null })
    show(store)

    const before = Date.now()
    await user.selectOptions(screen.getByRole('combobox', { name: 'Status of Alpha by Author' }), 'READING')
    const started = store.book('a')?.dateStarted ?? 0
    expect(started).toBeGreaterThanOrEqual(before)
    expect(store.book('a')?.status).toBe('READING')

    await user.selectOptions(screen.getByRole('combobox', { name: 'Status of Alpha by Author' }), 'READ')
    expect(store.book('a')).toMatchObject({ status: 'READ', dateStarted: started })
    expect(store.book('a')?.dateRead).toBeGreaterThanOrEqual(before)

    // To read forgets the dates and the progress, as everywhere else.
    await user.selectOptions(screen.getByRole('combobox', { name: 'Status of Bravo by Author' }), 'TO_READ')
    expect(store.book('b')).toMatchObject({ status: 'TO_READ', dateStarted: null, dateRead: null, currentPage: null, progressPercent: null })
    expect(screen.getByRole('combobox', { name: 'Status of Bravo by Author' })).toHaveValue('TO_READ')
  }, T)

  it('arrowKeysOnTheTableStatusDoNotChangeItUntilChosen', async () => {
    const store = await open([
      book('a', { title: 'Alpha', status: 'READING', dateStarted: 5, currentPage: 3, progressPercent: 10 }),
    ])
    const user = userEvent.setup({ delay: null })
    show(store)
    const select = screen.getByRole('combobox', { name: 'Status of Alpha by Author' })

    // A focused, closed select fires `change` on every arrow key (Chrome, Edge): that is browsing, not choosing.
    select.focus()
    fireEvent.change(select, { target: { value: 'READ' } })
    fireEvent.change(select, { target: { value: 'TO_READ' } })
    expect(store.book('a')).toMatchObject({ status: 'READING', dateStarted: 5, currentPage: 3, progressPercent: 10 })
    expect(select).toHaveValue('TO_READ')

    // Enter chooses it.
    fireEvent.keyDown(select, { key: 'Enter' })
    expect(store.book('a')).toMatchObject({ status: 'TO_READ', dateStarted: null, currentPage: null, progressPercent: null })

    // So does leaving the control.
    fireEvent.change(select, { target: { value: 'READ' } })
    expect(store.book('a')?.status).toBe('TO_READ')
    await user.tab()
    expect(store.book('a')?.status).toBe('READ')
  }, T)

  it('aTableStatusChangeCanBeUndone', async () => {
    const store = await open([
      book('a', { title: 'Alpha', status: 'READING', dateStarted: 5, currentPage: 3, progressPercent: 10, rating: 2 }),
    ])
    const user = userEvent.setup({ delay: null })
    show(store)

    await user.selectOptions(screen.getByRole('combobox', { name: 'Status of Alpha by Author' }), 'READ')
    expect(store.book('a')?.status).toBe('READ')
    expect(screen.getByRole('status')).toHaveTextContent('Status changed to Read.')
    // A newer edit to a field the change didn't write stays; one it did write is brought back only if still as written.
    store.edit('books', 'a', { rating: 5 })
    await user.click(screen.getByRole('button', { name: 'Undo' }))
    expect(store.book('a')).toMatchObject({
      status: 'READING', dateStarted: 5, dateRead: null, currentPage: 3, progressPercent: 10, rating: 5,
    })

    // A field changed since keeps its newer value.
    await user.selectOptions(screen.getByRole('combobox', { name: 'Status of Alpha by Author' }), 'TO_READ')
    store.edit('books', 'a', { status: 'READ' })
    await user.click(screen.getByRole('button', { name: 'Undo' }))
    expect(store.book('a')?.status).toBe('READ')
    expect(store.book('a')).toMatchObject({ dateStarted: 5, currentPage: 3, progressPercent: 10 })
  }, T)

  it('afterDeletingFocusIsOnUndo', async () => {
    const store = await open([book('a', { title: 'Alpha', dateAdded: 2 }), book('b', { title: 'Bravo', dateAdded: 1 })])
    const user = userEvent.setup({ delay: null })
    show(store)

    await user.click(screen.getByRole('checkbox', { name: 'Select Alpha' }))
    await user.keyboard('{Delete}')
    expect(store.books().map((b) => b.id)).toEqual(['b'])
    expect(screen.getByRole('button', { name: 'Undo' })).toHaveFocus()

    // Undo gives focus back to the page: the heading, as the row it came from is gone.
    await user.keyboard('{Enter}')
    expect(store.books()).toHaveLength(2)
    expect(screen.getByRole('heading', { level: 1, name: 'Library' })).toHaveFocus()
  }, T)

  it('clickingAStarInTheTableSetsTheScore', async () => {
    const store = await open([book('a', { title: 'Stoner', authors: 'John Williams', rating: null })])
    const user = userEvent.setup({ delay: null })
    show(store)

    await user.click(screen.getByRole('button', { name: '3 stars for Stoner by John Williams' }))
    expect(store.book('a')?.rating).toBe(3)
    expect(screen.getByRole('button', { name: '3 stars for Stoner by John Williams' })).toHaveAttribute('aria-pressed', 'true')
    expect(screen.getByRole('button', { name: '1 star for Stoner by John Williams' })).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: '5 stars for Stoner by John Williams' }))
    expect(store.book('a')?.rating).toBe(5)

    // The current star again clears it, as on the book page.
    await user.click(screen.getByRole('button', { name: '5 stars for Stoner by John Williams' }))
    expect(store.book('a')?.rating).toBeNull()
  }, T)

  it('coversViewShowsTheFilteredBooks', async () => {
    const store = await open([
      book('a', { title: 'Alpha', authors: 'Ann', status: 'READ', rating: 4, dateAdded: 3 }),
      book('b', { title: 'Bravo', authors: 'Ben', status: 'READING', dateAdded: 2 }),
      book('c', { title: 'Charlie', authors: 'Cy', status: 'READ', dateAdded: 1 }),
    ])
    const user = userEvent.setup({ delay: null })
    const { onOpen } = show(store)
    const view = screen.getByRole('group', { name: 'View' })

    await user.click(within(view).getByRole('button', { name: 'Covers' }))
    expect(screen.queryByRole('table')).not.toBeInTheDocument()
    const grid = screen.getByRole('list', { name: 'Covers' })
    expect(within(grid).getAllByRole('listitem')).toHaveLength(3)
    // No checkboxes: choosing books and bulk changes are for the table.
    expect(screen.queryAllByRole('checkbox')).toHaveLength(0)

    await user.selectOptions(screen.getByLabelText('Filter by status'), 'READ')
    expect(within(grid).getAllByRole('listitem').map((li) => li.querySelector('.cover-card__title')?.textContent)).toEqual([
      'Alpha', 'Charlie',
    ])
    expect([...grid.querySelectorAll('.cover-card__author')].map((a) => a.textContent)).toEqual(['Ann', 'Cy'])
    expect(within(grid).getByRole('img', { name: '4 of 5' })).toBeInTheDocument()
    expect(within(grid).getAllByRole('img', { name: /of 5/ })).toHaveLength(1)

    await user.click(within(grid).getByRole('link', { name: /Charlie/ }))
    expect(onOpen).toHaveBeenCalledWith('c')

    await user.click(within(view).getByRole('button', { name: 'Table' }))
    expect(titles()).toEqual(['Alpha', 'Charlie'])
  }, T)

  it('phonesDefaultToCoversUnlessChosen', async () => {
    const store = await open([book('a', { title: 'Alpha' })])
    const user = userEvent.setup({ delay: null })
    setPhone(true)
    const first = show(store)
    // A phone with no choice made yet: the table is too wide there, so the covers.
    expect(screen.getByRole('button', { name: 'Covers' })).toHaveAttribute('aria-pressed', 'true')
    expect(screen.queryByRole('table')).not.toBeInTheDocument()

    // A choice made on the phone is kept, and wins over the default.
    await user.click(screen.getByRole('button', { name: 'Table' }))
    first.unmount()
    show(store)
    expect(screen.getByRole('button', { name: 'Table' })).toHaveAttribute('aria-pressed', 'true')
    expect(screen.getByRole('table')).toBeInTheDocument()
  }, T)

  it('aWideScreenStillDefaultsToTheTable', async () => {
    const store = await open([book('a', { title: 'Alpha' })])
    setPhone(false)
    show(store)
    expect(screen.getByRole('button', { name: 'Table' })).toHaveAttribute('aria-pressed', 'true')
  }, T)

  it('theViewChoiceIsRemembered', async () => {
    const store = await open([book('a', { title: 'Alpha' })])
    const user = userEvent.setup({ delay: null })
    const page = show(store)
    expect(screen.getByRole('button', { name: 'Table' })).toHaveAttribute('aria-pressed', 'true')
    await user.click(screen.getByRole('button', { name: 'Covers' }))
    expect(window.localStorage.getItem(VIEW_STORAGE_KEY)).toBe('covers')

    page.unmount()
    page.again()
    expect(screen.getByRole('button', { name: 'Covers' })).toHaveAttribute('aria-pressed', 'true')
    expect(screen.queryByRole('table')).not.toBeInTheDocument()
  }, T)

  it('clickingATagChipFiltersByThatTag', async () => {
    const store = await open([
      book('a', { title: 'Alpha', tags: ['fantasy', 'owned'], dateAdded: 3 }),
      book('b', { title: 'Bravo', tags: ['history'], dateAdded: 2 }),
      book('c', { title: 'Charlie', tags: ['fantasy'], dateAdded: 1 }),
    ])
    const user = userEvent.setup({ delay: null })
    show(store)
    const table = screen.getByRole('table')

    await user.click(within(table).getAllByRole('button', { name: 'fantasy' })[0])
    expect(titles()).toEqual(['Alpha', 'Charlie'])
    expect(screen.getByLabelText('Filter by tag')).toHaveValue('fantasy')
    await user.click(screen.getByRole('button', { name: 'Clear filters' }))
    expect(titles()).toEqual(['Alpha', 'Bravo', 'Charlie'])
  }, T)

  it('undoingADeleteKeepsANewerPhoneEdit', async () => {
    const store = await openWithPhone([book('a', { title: 'Alpha', rating: 2 })])
    const user = userEvent.setup({ delay: null })
    show(store)
    await user.click(screen.getByRole('checkbox', { name: 'Select Alpha' }))
    await user.click(screen.getByRole('button', { name: 'Delete' }))

    // Within the eight seconds the phone changes the title and the score.
    phone.later.push({
      table: 'books', id: 'a', fields: { title: 'Alpha, revised', rating: 5 },
      times: { title: Date.now() + 60_000, rating: Date.now() + 60_000 }, seq: 1,
    })
    await store.refresh()
    await user.click(screen.getByRole('button', { name: 'Undo' }))

    // Only the book coming back is written: the phone's values stay.
    expect(store.book('a')).toMatchObject({ title: 'Alpha, revised', rating: 5 })
  }, T)

  it('undoStillBringsBackABookTheServerForgot', async () => {
    const store = await openWithPhone([book('a', { title: 'Alpha' })])
    store.createQuote({ id: 'q1', bookId: 'a', text: 'One', page: null, dateAdded: 1, position: null, deleted: false })
    const user = userEvent.setup({ delay: null })
    show(store)
    await user.click(screen.getByRole('checkbox', { name: 'Select Alpha' }))
    await user.click(screen.getByRole('button', { name: 'Delete' }))
    // The server's copy comes back as deleted, so the site drops the rows for good.
    phone.later.push(
      { table: 'books', id: 'a', fields: { deleted: true }, times: { deleted: Date.now() + 60_000 }, seq: 1 },
      { table: 'quotes', id: 'q1', fields: { deleted: true }, times: { deleted: Date.now() + 60_000 }, seq: 2 },
    )
    await store.refresh()
    await user.click(screen.getByRole('button', { name: 'Undo' }))
    expect(store.book('a')).toMatchObject({ title: 'Alpha', deleted: false })
    expect(store.quotes('a').map((q) => q.text)).toEqual(['One'])
  }, T)

  it('undoAfterTheServerForgotKeepsANewerPhoneEdit', async () => {
    const pushed: { table: string; id: string; fields: Record<string, unknown>; times: Record<string, number> }[] = []
    const recording: SyncApi = {
      ...phoneApi,
      push: async (changes) => {
        pushed.push(...changes.map((c) => ({ table: c.table, id: c.id, fields: c.fields, times: c.times })))
        return { applied: changes.map((c) => rowKey(c.table, c.id)), rejected: [] }
      },
    }
    const store = new LibraryStore({ api: recording, owner: 'u1', retryDelays: [60_000], pollMs: 0 })
    stores.push(store)
    await store.load()
    store.create(book('a', { title: 'Alpha', rating: 2 }))
    const original = store.bookTimes('a')
    const user = userEvent.setup({ delay: null })
    show(store)
    await user.click(screen.getByRole('checkbox', { name: 'Select Alpha' }))
    await user.click(screen.getByRole('button', { name: 'Delete' }))

    // The server echoes the delete (so the site drops the row for good), then the phone, which still saw the book,
    // changes its score later than anything the site knew.
    const echoed = Date.now() - 5
    const phoneTime = Date.now() + 60_000
    phone.later.push({ table: 'books', id: 'a', fields: { deleted: true }, times: { deleted: echoed }, seq: 1 })
    await store.refresh()
    expect(store.book('a')).toBeUndefined()
    pushed.length = 0
    await user.click(screen.getByRole('button', { name: 'Undo' }))
    await store.idle()

    // The row goes back with the times it had, and only `deleted` is new: the server keeps the newer score.
    const row = pushed.filter((c) => c.table === 'books' && c.id === 'a').pop()
    expect(row?.fields).toMatchObject({ title: 'Alpha', deleted: false })
    expect(row?.times.rating).toBe(original.rating)
    expect(row?.times.title).toBe(original.title)
    expect(row?.times.deleted).toBeGreaterThan(echoed)
    const server: SyncRow = {
      table: 'books', id: 'a', fields: { ...row?.fields, rating: 5, deleted: true },
      times: { ...row?.times, rating: phoneTime, deleted: echoed },
    }
    const merged = mergeFields(server, { table: 'books', id: 'a', fields: row?.fields ?? {}, times: row?.times ?? {} })
    expect(merged.fields).toMatchObject({ rating: 5, deleted: false })
  }, T)

  it('deletingBooksWithPhoneDataAsksFirst', async () => {
    phone.first = [{ table: 'bookmarks', id: 'm1', fields: { book_id: 'a', deleted: false }, times: { book_id: 1 }, seq: 1 }]
    const store = await openWithPhone([
      book('a', { title: 'Alpha', dateAdded: 3 }),
      book('b', { title: 'Bravo', dateAdded: 2 }),
      book('c', { title: 'Charlie', dateAdded: 1 }),
    ])
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(false)
    const user = userEvent.setup({ delay: null })
    show(store)

    await user.click(screen.getByRole('checkbox', { name: 'Select Alpha' }))
    await user.click(screen.getByRole('checkbox', { name: 'Select Bravo' }))
    await user.click(screen.getByRole('button', { name: 'Delete' }))
    expect(confirm).toHaveBeenCalledWith(
      "1 of these books have bookmarks or an e-book on your phone. Those won't come back with Undo. Delete anyway?",
    )
    expect(store.books()).toHaveLength(3)
    expect(screen.queryByRole('button', { name: 'Undo' })).not.toBeInTheDocument()

    confirm.mockReturnValue(true)
    await user.click(screen.getByRole('button', { name: 'Delete' }))
    expect(store.books().map((b) => b.id)).toEqual(['c'])
    expect(screen.getByRole('button', { name: 'Undo' })).toBeInTheDocument()

    // Books without phone data are never asked about.
    confirm.mockClear()
    await user.click(screen.getByRole('checkbox', { name: 'Select Charlie' }))
    await user.click(screen.getByRole('button', { name: 'Delete' }))
    expect(confirm).not.toHaveBeenCalled()
  }, T)

  it('aBadShelfValueOnBlurSaysSo', async () => {
    const store = await open([
      book('a', { title: 'Alpha', status: 'READING', dateStarted: 1, pageCount: 200, currentPage: 50, progressPercent: 25 }),
    ])
    const user = userEvent.setup({ delay: null })
    show(store)
    const shelf = screen.getByRole('region', { name: 'Reading now' })
    await user.click(within(shelf).getByRole('button', { name: 'Update progress for Alpha' }))
    const input = within(shelf).getByRole('spinbutton', { name: 'Current page of Alpha' })
    await user.clear(input)
    await user.type(input, '-4')
    await user.tab()
    expect(within(shelf).getByRole('spinbutton', { name: 'Current page of Alpha' })).toHaveAttribute('aria-invalid', 'true')
    expect(within(shelf).getByRole('alert')).toHaveTextContent('Type a page number')
    expect(store.book('a')).toMatchObject({ currentPage: 50, progressPercent: 25 })
  }, T)

  it('coversViewCanBeSorted', async () => {
    const store = await open([
      book('a', { title: 'Bravo', authors: 'Zed', dateAdded: 3, dateRead: 100 }),
      book('b', { title: 'Alpha', authors: 'Mia', dateAdded: 2, dateRead: 300 }),
      book('c', { title: 'Charlie', authors: 'Ann', dateAdded: 1, dateRead: null }),
    ])
    const user = userEvent.setup({ delay: null })
    show(store)
    await user.click(screen.getByRole('button', { name: 'Covers' }))
    const order = () => [...document.querySelectorAll('.cover-card__title')].map((t) => t.textContent)
    const sort = screen.getByRole('combobox', { name: 'Sort covers' })

    expect(order()).toEqual(['Bravo', 'Alpha', 'Charlie'])
    await user.selectOptions(sort, 'Title')
    expect(order()).toEqual(['Alpha', 'Bravo', 'Charlie'])
    await user.selectOptions(sort, 'Author')
    expect(order()).toEqual(['Charlie', 'Alpha', 'Bravo'])
    await user.selectOptions(sort, 'Recently read')
    expect(order()).toEqual(['Alpha', 'Bravo', 'Charlie'])
    await user.selectOptions(sort, 'Recently added')
    expect(order()).toEqual(['Bravo', 'Alpha', 'Charlie'])
  }, T)

  it('theLibraryShowsTheQuoteOfTheDay', async () => {
    const store = await open([book('1')])
    const day = new Date(2026, 10, 16, 12)
    const quote = quoteOfTheDay(day)
    const fetchImpl = vi.fn().mockRejectedValue(new Error('offline'))
    render(
      <ToastProvider>
        <LibraryPage store={store} year={2026} onAdd={vi.fn()} onOpen={vi.fn()} quoteDay={day} quoteFetch={fetchImpl} />
      </ToastProvider>,
    )
    expect(screen.getByRole('blockquote')).toHaveAttribute('lang', quote.lang)
    expect(screen.getByRole('link', { name: quote.author })).toBeInTheDocument()
    // It lives in the band of the header and the shelf, which holds it in; not a layer over the whole page.
    const band = screen.getByRole('blockquote').closest('.library-band')
    expect(band).not.toBeNull()
    expect(band).toContainElement(screen.getByRole('heading', { name: 'Library' }))
    expect(band).not.toContainElement(screen.getByRole('searchbox', { name: 'Search' }))
    await waitFor(() => expect(fetchImpl).toHaveBeenCalled())
  }, T)
})
