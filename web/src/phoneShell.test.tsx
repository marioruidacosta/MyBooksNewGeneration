import 'fake-indexeddb/auto'
import { IDBFactory } from 'fake-indexeddb'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

const h = vi.hoisted(() => ({ signOut: vi.fn(() => Promise.resolve({ error: null })) }))

vi.mock('./supabase', () => ({
  supabase: {
    auth: {
      getSession: () => Promise.resolve({ data: { session: { user: { id: 'u1' } } }, error: null }),
      onAuthStateChange: () => ({ data: { subscription: { unsubscribe: () => {} } } }),
      signOut: h.signOut,
    },
    storage: {},
  },
}))

import { App } from './App'
import { rowKey, type SyncApi } from './sync/api'
import { bookToFields } from './sync/codec'
import type { Book, SyncRow } from './sync/model'
import { clearMedia, setPhone } from './test-media'

const api: SyncApi = {
  pull: async () => ({ rows: [], last: 0 }),
  push: async (changes) => ({ applied: changes.map((c) => rowKey(c.table, c.id)), rejected: [] }),
  onPing: () => () => {},
}

beforeEach(() => {
  globalThis.indexedDB = new IDBFactory()
  window.localStorage.clear()
  window.history.pushState({}, '', '/MyBooksNewGeneration/app/')
})
afterEach(() => clearMedia())

describe('the phone layout', () => {
  it('theBottomBarReachesEveryPage', async () => {
    setPhone(true)
    const user = userEvent.setup({ delay: null })
    render(<App createApi={() => api} />)

    const bar = await screen.findByRole('navigation', { name: 'Main' })
    // The top bar's six links are gone on a phone: this bar replaces them.
    expect(screen.queryByRole('navigation', { name: 'Pages' })).not.toBeInTheDocument()
    const href = (name: string) => within(bar).getByRole('link', { name }).getAttribute('href')
    expect(href('Library')).toBe('/MyBooksNewGeneration/app/')
    expect(href('Add')).toBe('/MyBooksNewGeneration/app/add')
    expect(href('Community')).toBe('/MyBooksNewGeneration/app/community')
    expect(href('Challenges')).toBe('/MyBooksNewGeneration/app/challenges')
    // The page being shown is marked, for assistive technology and for the eye.
    expect(within(bar).getByRole('link', { name: 'Library' })).toHaveAttribute('aria-current', 'page')

    // The rest is under More: the year, the news, Import, Tidy up, the theme and signing out.
    const more = within(bar).getByRole('button', { name: 'More' })
    expect(more).toHaveAttribute('aria-expanded', 'false')
    await user.click(more)
    expect(more).toHaveAttribute('aria-expanded', 'true')
    const menu = screen.getByRole('dialog', { name: 'More' })
    expect(within(menu).getByRole('link', { name: 'Year in books' }).getAttribute('href')).toBe('/MyBooksNewGeneration/app/year')
    expect(within(menu).getByRole('link', { name: 'Physical library' }).getAttribute('href')).toBe('/MyBooksNewGeneration/app/physical')
    expect(within(menu).getByRole('link', { name: 'Book news' }).getAttribute('href')).toBe('/MyBooksNewGeneration/app/news')
    expect(within(menu).getByRole('link', { name: 'Import' }).getAttribute('href')).toBe('/MyBooksNewGeneration/app/import')
    expect(within(menu).getByRole('link', { name: 'Tidy up' }).getAttribute('href')).toBe('/MyBooksNewGeneration/app/tidy')
    expect(within(menu).getByLabelText('Theme')).toBeInTheDocument()
    await user.click(within(menu).getByRole('button', { name: 'Sign out' }))
    expect(h.signOut).toHaveBeenCalled()
  })

  it('theMoreMenuClosesOnEscapeAndWhenAPageIsChosen', async () => {
    setPhone(true)
    const user = userEvent.setup({ delay: null })
    render(<App createApi={() => api} />)
    const bar = await screen.findByRole('navigation', { name: 'Main' })
    await user.click(within(bar).getByRole('button', { name: 'More' }))
    await user.keyboard('{Escape}')
    expect(screen.queryByRole('dialog', { name: 'More' })).not.toBeInTheDocument()

    await user.click(within(bar).getByRole('button', { name: 'More' }))
    await user.click(within(screen.getByRole('dialog', { name: 'More' })).getByRole('link', { name: 'Import' }))
    expect(screen.queryByRole('dialog', { name: 'More' })).not.toBeInTheDocument()
    expect(await screen.findByRole('heading', { level: 1, name: /Import/ })).toBeInTheDocument()
    // The bar is still there, and More says it holds the page being shown.
    expect(within(screen.getByRole('navigation', { name: 'Main' })).getByRole('button', { name: 'More' })).toHaveAttribute('data-current', 'true')
  })

  it('theMoreSheetTakesFocusKeepsTabInsideAndGivesFocusBack', async () => {
    setPhone(true)
    const user = userEvent.setup({ delay: null })
    render(<App createApi={() => api} />)
    const bar = await screen.findByRole('navigation', { name: 'Main' })
    const more = within(bar).getByRole('button', { name: 'More' })

    await user.click(more)
    const menu = screen.getByRole('dialog', { name: 'More' })
    expect(within(menu).getByRole('link', { name: 'Year in books' })).toHaveFocus()

    // Tab never leaves the sheet: from the last item it wraps to the first, and back.
    await user.tab({ shift: true })
    expect(within(menu).getByRole('button', { name: 'Sign out' })).toHaveFocus()
    await user.tab()
    expect(within(menu).getByRole('link', { name: 'Year in books' })).toHaveFocus()

    // Escape, and a tap outside, return focus to More.
    await user.keyboard('{Escape}')
    expect(more).toHaveFocus()
    await user.click(more)
    await user.click(document.querySelector('.more-backdrop') as HTMLElement)
    expect(screen.queryByRole('dialog', { name: 'More' })).not.toBeInTheDocument()
    expect(more).toHaveFocus()

    // Choosing a page leaves focus somewhere that still exists: the new page's heading.
    await user.click(more)
    await user.click(within(screen.getByRole('dialog', { name: 'More' })).getByRole('link', { name: 'Tidy up' }))
    await vi.waitFor(() => expect(screen.getByRole('heading', { level: 1 })).toHaveFocus())
    expect(document.body).not.toHaveFocus()
  })

  it('choosingYearOrNewsFromTheSheetPutsFocusOnTheNewPage', async () => {
    setPhone(true)
    const user = userEvent.setup({ delay: null })
    render(<App createApi={() => api} />)
    const bar = await screen.findByRole('navigation', { name: 'Main' })
    await user.click(within(bar).getByRole('button', { name: 'More' }))
    await user.click(within(screen.getByRole('dialog', { name: 'More' })).getByRole('link', { name: 'Year in books' }))
    await vi.waitFor(() => expect(screen.getByRole('heading', { level: 1, name: 'Year in books' })).toHaveFocus())
  })

  it('theJumpBoxStaysReachableWithASearchIconInsteadOfTheKeyHint', async () => {
    setPhone(true)
    render(<App createApi={() => api} />)
    expect(await screen.findByRole('button', { name: 'Jump to a book' })).toBeInTheDocument()
    expect(screen.queryByText(/Ctrl K|⌘ K/)).not.toBeInTheDocument()
  })

  it('aWideScreenKeepsTheTopBarAsItWas', async () => {
    setPhone(false)
    render(<App createApi={() => api} />)
    const pages = await screen.findByRole('navigation', { name: 'Pages' })
    expect(within(pages).getAllByRole('link').map((a) => a.textContent)).toEqual([
      'Library', 'Add a book', 'Community', 'Challenges', 'Physical library', 'Import', 'Tidy up', 'Year in books', 'Book news',
    ])
    expect(screen.queryByRole('navigation', { name: 'Main' })).not.toBeInTheDocument()
    expect(screen.getByText(/Ctrl K|⌘ K/)).toBeInTheDocument()
  })
})

describe('going back from a book', () => {
  const stoner: Book = {
    id: 'b1', isbn: null, title: 'Stoner', authors: 'John Williams', publisher: null, publishedYear: null, pageCount: null,
    coverUrl: null, description: null, status: 'READ', rating: null, notes: '', dateAdded: 1, dateRead: null, dateStarted: null,
    currentPage: null, progressPercent: null, review: '', tags: [], series: null, seriesNumber: null, lentTo: null, lentOn: null,
    shelfId: null, shelfPosition: null, deleted: false,
  }
  const bookcase = JSON.stringify({ v: 1, name: 'By the window', room: 'Living room', columns: [{ id: 'c', shelves: ['s1'] }] })
  const apiWith = (book: Book): SyncApi => {
    const fields = bookToFields(book)
    const rows: SyncRow[] = [
      { table: 'books', id: book.id, fields, times: Object.fromEntries(Object.keys(fields).map((f) => [f, 1])), seq: 1 },
      { table: 'prefs', id: 'bookcase.w', fields: { value: bookcase, deleted: false }, times: { value: 1, deleted: 1 }, seq: 2 },
    ]
    return { ...api, pull: async (after) => (after === 0 ? { rows, last: 2 } : { rows: [], last: after }) }
  }

  it('aBookOpenedFromAShelfGoesBackToIt', async () => {
    window.history.pushState({}, '', '/MyBooksNewGeneration/app/physical/shelf/s1')
    const user = userEvent.setup({ delay: null })
    render(<App createApi={() => apiWith({ ...stoner, shelfId: 's1', shelfPosition: 1 })} />)
    await user.click(await screen.findByRole('button', { name: /^Stoner/ }))
    await user.click(await screen.findByRole('button', { name: 'Back to the shelf' }))
    expect(await screen.findByRole('heading', { name: 'Shelf A1' })).toBeInTheDocument()
  })

  it('aBookOpenedFromWithoutAPlaceGoesBackToIt', async () => {
    window.history.pushState({}, '', '/MyBooksNewGeneration/app/physical/unplaced')
    const user = userEvent.setup({ delay: null })
    render(<App createApi={() => apiWith(stoner)} />)
    await user.click(await screen.findByRole('button', { name: /^Stoner/ }))
    await user.click(await screen.findByRole('button', { name: 'Back to Without a place' }))
    expect(await screen.findByRole('heading', { name: 'Without a place' })).toBeInTheDocument()
  })

  it('aBookFoundInThePhysicalLibraryGoesBackToTheSearch', async () => {
    window.history.pushState({}, '', '/MyBooksNewGeneration/app/physical')
    const user = userEvent.setup({ delay: null })
    render(<App createApi={() => apiWith({ ...stoner, shelfId: 's1', shelfPosition: 1 })} />)
    await user.type(await screen.findByLabelText('Find a book by title or author'), 'stoner')
    await user.click(await screen.findByRole('button', { name: /^Stoner/ }))
    await user.click(await screen.findByRole('button', { name: 'Back to Physical library' }))
    expect(await screen.findByLabelText('Find a book by title or author')).toHaveValue('stoner')
    expect(await screen.findByText(/Shelf A1 · 1st from the left/)).toBeInTheDocument()
  })

  it('aBookOpenedFromTidyUpGoesBackToIt', async () => {
    window.history.pushState({}, '', '/MyBooksNewGeneration/app/tidy')
    const user = userEvent.setup({ delay: null })
    render(<App createApi={() => apiWith(stoner)} />)
    await user.click(await screen.findByRole('button', { name: /^Stoner/ }))
    await user.click(await screen.findByRole('button', { name: 'Back to Tidy up' }))
    expect(await screen.findByRole('heading', { name: 'Tidy up' })).toBeInTheDocument()
  })

  it('anUnknownOriginGoesBackToTheLibrary', async () => {
    window.history.pushState({}, '', '/MyBooksNewGeneration/app/book/nope?from=https://example.com')
    render(<App createApi={() => api} />)
    expect(await screen.findByRole('button', { name: 'Back to the library' })).toBeInTheDocument()
  })
})
