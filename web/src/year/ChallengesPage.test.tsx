import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { IDBFactory } from 'fake-indexeddb'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { SyncApi } from '../sync/api'
import { rowKey } from '../sync/api'
import type { Book } from '../sync/model'
import { LibraryStore } from '../sync/store'
import { ChallengesPage } from './ChallengesPage'

const api: SyncApi = {
  pull: async () => ({ rows: [], last: 0 }),
  push: async (changes) => ({ applied: changes.map((c) => rowKey(c.table, c.id)), rejected: [] }),
  onPing: () => () => {},
}

function book(id: string, over: Partial<Book> = {}): Book {
  return {
    id, isbn: null, title: id, authors: 'Someone', publisher: null, publishedYear: null, pageCount: null, coverUrl: null,
    description: null, status: 'READ', rating: null, notes: '', dateAdded: 0, dateRead: null, dateStarted: null,
    currentPage: null, progressPercent: null, review: '', tags: [], series: null, seriesNumber: null, lentTo: null,
    lentOn: null, deleted: false, ...over,
  }
}
const day = (y: number, m: number, d: number) => new Date(y, m - 1, d, 12).getTime()
const TODAY = new Date(2026, 10, 3)

let store: LibraryStore

beforeEach(async () => {
  globalThis.indexedDB = new IDBFactory()
  store = new LibraryStore({ api, owner: 'u1', pollMs: 0, retryDelays: [60_000] })
  await store.load()
})
afterEach(async () => {
  await store.close()
})

const show = (onOpen = vi.fn(), onOpenYear = vi.fn()) => {
  render(<ChallengesPage store={store} today={() => TODAY} onOpen={onOpen} onOpenYear={onOpenYear} />)
  return { onOpen, onOpenYear }
}

describe('reading challenges', () => {
  it('thisYearsChallengeShowsCountAndPace', () => {
    for (let i = 0; i < 27; i++) store.create(book(`b${i}`, { dateRead: day(2026, 3, 1) }))
    store.edit('challenges', '2026', { goal: 30 })
    show()
    expect(screen.getByRole('heading', { name: '2026 Reading Challenge' })).toBeInTheDocument()
    expect(screen.getByText('27 of 30 books')).toBeInTheDocument()
    expect(screen.getByText('2 books ahead of schedule')).toBeInTheDocument()
    expect(screen.getByRole('progressbar', { name: '2026 challenge progress' })).toHaveAttribute('aria-valuenow', '90')
  })

  it('withoutAGoalItOffersToSetOne', async () => {
    const user = userEvent.setup()
    show()
    await user.click(screen.getByRole('button', { name: 'Set 2026 goal' }))
    const dialog = screen.getByRole('dialog', { name: 'Set 2026 goal' })
    await user.type(within(dialog).getByLabelText('Books to read'), '24')
    await user.click(within(dialog).getByRole('button', { name: 'Save' }))
    expect(store.goal(2026)).toBe(24)
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })

  it('aBadGoalIsExplainedAndNotSaved', async () => {
    const user = userEvent.setup()
    show()
    await user.click(screen.getByRole('button', { name: 'Set 2026 goal' }))
    const dialog = screen.getByRole('dialog')
    await user.type(within(dialog).getByLabelText('Books to read'), '1000')
    await user.click(within(dialog).getByRole('button', { name: 'Save' }))
    expect(within(dialog).getByRole('alert')).toHaveTextContent('Enter a number of books from 1 to 999')
    expect(store.goal(2026)).toBeNull()
  })

  it('aNewChallengeCanBeForNextYear', async () => {
    const user = userEvent.setup()
    show()
    await user.click(screen.getByRole('button', { name: 'New challenge' }))
    const dialog = screen.getByRole('dialog', { name: 'New challenge' })
    expect(within(dialog).getByLabelText('Year')).toHaveValue('2027')
    await user.type(within(dialog).getByLabelText('Books to read'), '12')
    await user.click(within(dialog).getByRole('button', { name: 'Save' }))
    expect(store.goal(2027)).toBe(12)
    expect(screen.getByText('2027 · 0 of 12 books')).toBeInTheDocument()
    expect(screen.getByText('Starts on 1 January')).toBeInTheDocument()
  })

  it('aGoalCanBeEditedAndRemoved', async () => {
    const user = userEvent.setup()
    store.edit('challenges', '2025', { goal: 10 })
    show()
    await user.click(screen.getByRole('button', { name: 'Edit 2025 goal' }))
    let dialog = screen.getByRole('dialog', { name: 'Edit 2025 goal' })
    const goal = within(dialog).getByLabelText('Books to read')
    expect(goal).toHaveValue('10')
    await user.clear(goal)
    await user.type(goal, '15')
    await user.click(within(dialog).getByRole('button', { name: 'Save' }))
    expect(store.goal(2025)).toBe(15)

    await user.click(screen.getByRole('button', { name: 'Edit 2025 goal' }))
    dialog = screen.getByRole('dialog')
    await user.click(within(dialog).getByRole('button', { name: 'Remove' }))
    expect(store.goal(2025)).toBeNull()
    expect(screen.queryByText(/^2025 ·/)).not.toBeInTheDocument()
  })

  it('otherYearsListWithTheirOutcomeAndOpen', async () => {
    const user = userEvent.setup()
    store.create(book('past', { dateRead: day(2025, 5, 1) }))
    store.edit('challenges', '2025', { goal: 2 })
    const { onOpenYear } = show()
    expect(screen.getByText('Ended 1 book short')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: '2025 · 1 of 2 books' }))
    expect(onOpenYear).toHaveBeenCalledWith(2025)
  })

  it('theBooksReadThisYearOpen', async () => {
    const user = userEvent.setup()
    store.create(book('dune', { title: 'Dune', dateRead: day(2026, 2, 1) }))
    const { onOpen } = show()
    expect(screen.getByRole('heading', { name: 'Read in 2026 · 1' })).toBeInTheDocument()
    await user.click(screen.getByRole('link', { name: /Dune/ }))
    expect(onOpen).toHaveBeenCalledWith('dune')
  })

  it('saysWhenReadBooksHaveNoDate', () => {
    store.create(book('undated'))
    show()
    expect(screen.getByText(/1 read book has no finish date/)).toBeInTheDocument()
  })

  it('aGoalOfZeroCountsAsNoGoal', () => {
    store.edit('challenges', '2026', { goal: 0 })
    show()
    expect(screen.getByRole('button', { name: 'Set 2026 goal' })).toBeInTheDocument()
  })
})
