import 'fake-indexeddb/auto'
import { IDBFactory } from 'fake-indexeddb'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { rowKey, type SyncApi } from '../sync/api'
import type { Book } from '../sync/model'
import { LibraryStore } from '../sync/store'
import { YearPage } from './YearPage'

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

const read = (id: string, y: number, m: number, over: Partial<Book> = {}) =>
  book(id, { status: 'READ', dateRead: new Date(y, m, 15).getTime(), ...over })

const stores: LibraryStore[] = []
async function open(books: Book[], goal?: { year: number; goal: number }) {
  const store = new LibraryStore({ api, owner: 'u1', retryDelays: [60_000], pollMs: 0 })
  stores.push(store)
  await store.load()
  books.forEach((b) => store.create(b))
  if (goal) store.edit('challenges', String(goal.year), { goal: goal.goal })
  return store
}

const show = (store: LibraryStore, year = 2026) =>
  render(<YearPage store={store} year={year} thisYear={2026} onOpen={vi.fn()} />)

beforeEach(() => {
  globalThis.indexedDB = new IDBFactory()
})
afterEach(async () => {
  await Promise.all(stores.splice(0).map((s) => s.close()))
})

describe('YearPage', () => {
  it('yearShowsBooksPagesAndGoal', async () => {
    const store = await open(
      [
        read('a', 2026, 0, { pageCount: 300, rating: 5 }),
        read('b', 2026, 2, { pageCount: 200, rating: 4 }),
        read('c', 2026, 5),
        read('old', 2025, 5, { pageCount: 999 }),
        book('wish', { status: 'TO_READ', pageCount: 111 }),
      ],
      { year: 2026, goal: 12 },
    )
    show(store)
    expect(screen.getByText('2026')).toBeInTheDocument()
    expect(screen.getByText('3 of 12 books')).toBeInTheDocument()
    expect(screen.getByRole('progressbar', { name: '2026 goal progress' })).toHaveAttribute('aria-valuenow', '3')
    expect(screen.getByText('500 pages')).toBeInTheDocument()
    expect(screen.getByText('1 book has no page count')).toBeInTheDocument()
    expect(screen.getByLabelText('4.5 out of 5')).toBeInTheDocument()
    // The covers, in the order they were read.
    const covers = within(screen.getByRole('list', { name: 'Books read in 2026' })).getAllByRole('link')
    expect(covers.map((a) => a.getAttribute('aria-label'))).toEqual(['Book a', 'Book b', 'Book c'])
  })

  it('monthsCountTheBooksReadInEach', async () => {
    const store = await open([read('a', 2026, 0), read('b', 2026, 0), read('c', 2026, 2), read('d', 2025, 0)])
    show(store)
    const table = screen.getByRole('table', { name: 'Books read per month in 2026' })
    const rows = within(table).getAllByRole('row').slice(1)
    expect(rows).toHaveLength(12)
    const count = (month: string) => within(rows.find((r) => within(r).queryByText(month))!).getByRole('cell').textContent
    expect(count('Jan')).toBe('2')
    expect(count('Feb')).toBe('0')
    expect(count('Mar')).toBe('1')
  })

  it('topAuthorsAreOrderedByBooksRead', async () => {
    const store = await open([
      read('a', 2026, 0, { authors: 'Ana Silva' }),
      read('b', 2026, 1, { authors: 'Bruno Costa, Ana Silva' }),
      read('c', 2026, 2, { authors: 'Bruno Costa, Ana Silva' }),
      read('d', 2026, 3, { authors: 'Carla Dias' }),
      read('e', 2026, 3, { authors: 'Dora' }),
      read('f', 2026, 3, { authors: 'Eva' }),
      read('g', 2026, 3, { authors: 'Fay' }),
    ])
    show(store)
    const items = within(screen.getByRole('list', { name: 'Top authors' })).getAllByRole('listitem')
    expect(items).toHaveLength(5)
    expect(items[0]).toHaveTextContent('Ana Silva')
    expect(items[0]).toHaveTextContent('3 books')
    expect(items[1]).toHaveTextContent('Bruno Costa')
    expect(items[1]).toHaveTextContent('2 books')
    expect(items[2]).toHaveTextContent('Carla Dias')
    expect(items[2]).toHaveTextContent('1 book')
  })

  it('anEmptyYearSaysSo', async () => {
    const store = await open([read('a', 2026, 0)])
    const user = userEvent.setup()
    show(store)
    await user.click(screen.getByRole('button', { name: 'Previous year' }))
    expect(screen.getByText('2025')).toBeInTheDocument()
    expect(screen.getByText('No books finished in 2025 yet.')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Library' })).toBeInTheDocument()
    expect(screen.queryByRole('table')).not.toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Next year' }))
    expect(screen.getByRole('list', { name: 'Books read in 2026' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Next year' })).toBeDisabled()
  })

  it('authorsWhoseNamesComeInEitherOrderAreOne', async () => {
    const store = await open([
      read('a', 2026, 0, { authors: 'José Saramago' }),
      read('b', 2026, 1, { authors: 'Saramago Jose' }),
    ])
    show(store)
    const items = within(screen.getByRole('list', { name: 'Top authors' })).getAllByRole('listitem')
    expect(items).toHaveLength(1)
    expect(items[0]).toHaveTextContent('José Saramago')
    expect(items[0]).toHaveTextContent('2 books')
  })

  it('aHalfScoreDoesNotShowFullStars', async () => {
    const store = await open([read('a', 2026, 0, { rating: 5 }), read('b', 2026, 1, { rating: 4 })])
    const { container } = show(store)
    expect(container.querySelectorAll('.year-star--on')).toHaveLength(4)
    expect(container.querySelectorAll('.year-star--half')).toHaveLength(1)
  })
})
