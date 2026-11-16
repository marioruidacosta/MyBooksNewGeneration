import 'fake-indexeddb/auto'
import { IDBFactory } from 'fake-indexeddb'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { rowKey, SyncError, type SyncApi } from '../sync/api'
import type { Book } from '../sync/model'
import { LibraryStore } from '../sync/store'
import { CoverContext } from '../covers/storage'
import { COLUMN_STORAGE_KEY } from './columns'
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
  render(<LibraryPage store={store} year={2026} onAdd={onAdd} onOpen={onOpen} />)
  return { onAdd, onOpen }
}

/** The titles in the table, in row order. */
const titles = () =>
  within(screen.getByRole('table')).queryAllByRole('link').map((a) => a.textContent)

/** The same as `titles`, without the accessibility-tree walk of a role query: for a test that reads it dozens of times. */
const quickTitles = () => [...document.querySelectorAll('table a')].map((a) => a.textContent)

beforeEach(() => {
  globalThis.indexedDB = new IDBFactory()
  window.localStorage.clear()
})

afterEach(async () => {
  vi.restoreAllMocks()
  await Promise.all(stores.splice(0).map((s) => s.close()))
})

describe('LibraryPage', () => {
  it('searchIgnoresAccents', async () => {
    const store = await open([
      book('1', { title: 'Ensaio sobre a Cegueira', authors: 'José Saramago', isbn: '9789722015981' }),
      book('2', { title: 'Another', authors: 'Someone Else' }),
    ])
    const user = userEvent.setup()
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
    const user = userEvent.setup()
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
    await userEvent.setup().click(within(shelf).getByRole('link', { name: 'Alpha' }))
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
    const user = userEvent.setup()
    show(store)

    await user.click(screen.getByRole('checkbox', { name: 'Select Alpha' }))
    await user.click(screen.getByRole('checkbox', { name: 'Select Bravo' }))
    expect(screen.getByText('2 selected')).toBeInTheDocument()
    await user.selectOptions(screen.getByLabelText('Set status'), 'READING')

    // The book page's rule: Reading starts now.
    expect(edit.mock.calls).toEqual([
      ['books', 'a', { status: 'READING', date_started: NOW }],
      ['books', 'b', { status: 'READING', date_started: NOW }],
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
    const user = userEvent.setup()
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
    const user = userEvent.setup()
    show(store)

    for (const t of ['Alpha', 'Bravo', 'Charlie']) await user.click(screen.getByRole('checkbox', { name: `Select ${t}` }))
    await user.type(screen.getByLabelText('Tag'), 'new')
    await user.click(screen.getByRole('button', { name: 'Add tag' }))

    expect(store.book('a')?.tags).toEqual(['x', 'new'])
    expect(store.book('b')?.tags).toEqual(['x', 'new'])
    expect(store.book('c')?.tags).toEqual(['new'])
    // Bravo had it already: not touched.
    expect(edit.mock.calls).toEqual([
      ['books', 'a', { tags: ['x', 'new'] }],
      ['books', 'c', { tags: ['new'] }],
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
    const user = userEvent.setup()
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
    const user = userEvent.setup()
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

  it('deleteAsksFirst', async () => {
    const store = await open([
      book('a', { title: 'Alpha', dateAdded: 3 }),
      book('b', { title: 'Bravo', dateAdded: 2 }),
      book('c', { title: 'Charlie', dateAdded: 1 }),
    ])
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(false)
    const user = userEvent.setup()
    show(store)

    await user.click(screen.getByRole('checkbox', { name: 'Select Alpha' }))
    await user.click(screen.getByRole('checkbox', { name: 'Select Bravo' }))
    await user.click(screen.getByRole('button', { name: 'Delete' }))
    expect(confirm).toHaveBeenCalledWith('Delete 2 books?')
    expect(store.books()).toHaveLength(3)

    // The Delete key asks the same.
    await user.keyboard('{Delete}')
    expect(confirm).toHaveBeenCalledTimes(2)
    expect(store.books()).toHaveLength(3)

    confirm.mockReturnValue(true)
    await user.click(screen.getByRole('button', { name: 'Delete' }))
    expect(store.books().map((b) => b.id)).toEqual(['c'])
    expect(titles()).toEqual(['Charlie'])
    // The selection is gone with the books.
    expect(screen.queryByText('2 selected')).not.toBeInTheDocument()
  }, T)

  it('goalIsEditable', async () => {
    const store = await open([
      book('a', { status: 'READ', dateRead: new Date(2026, 2, 1).getTime() }),
      book('b', { status: 'READ', dateRead: new Date(2025, 2, 1).getTime() }),
    ])
    const edit = vi.spyOn(store, 'edit')
    const user = userEvent.setup()
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
    const user = userEvent.setup()
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
    const user = userEvent.setup()
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
    const user = userEvent.setup()
    show(store)
    await user.click(screen.getByRole('checkbox', { name: 'Select Alpha' }))
    await user.click(screen.getByRole('checkbox', { name: 'Select Bravo' }))
    await user.click(screen.getByRole('button', { name: 'Set series' }))
    for (const id of ['a', 'b']) {
      expect(store.book(id)?.series).toBeNull()
      expect(store.book(id)?.seriesNumber).toBeNull()
    }
  }, T)

  it('holdingDeleteAsksOnce', async () => {
    const store = await open([book('a', { title: 'Alpha', dateAdded: 3 })])
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(false)
    const user = userEvent.setup()
    show(store)
    await user.click(screen.getByRole('checkbox', { name: 'Select Alpha' }))

    fireEvent.keyDown(document.body, { key: 'Delete' })
    // The key held down repeats: those are not new presses.
    fireEvent.keyDown(document.body, { key: 'Delete', repeat: true })
    fireEvent.keyDown(document.body, { key: 'Delete', repeat: true })
    expect(confirm).toHaveBeenCalledTimes(1)
  }, T)
})
