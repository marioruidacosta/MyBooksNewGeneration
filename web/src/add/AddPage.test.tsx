import 'fake-indexeddb/auto'
import { IDBFactory } from 'fake-indexeddb'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { rowKey, type SyncApi } from '../sync/api'
import type { Book } from '../sync/model'
import { LibraryStore } from '../sync/store'
import { AddPage } from './AddPage'
import { NotFoundWithErrors, type Found, type Lookup } from './lookup'

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

const found = (over: Partial<Found> = {}): Found => ({
  isbn: '9789722015981', title: 'Blindness', authors: ['José Saramago'], publisher: null, year: 1995, pages: 300,
  coverUrl: null, description: null, ...over,
})

const stores: LibraryStore[] = []

async function open(books: Book[] = []) {
  const store = new LibraryStore({ api, owner: 'u1', retryDelays: [60_000], pollMs: 0 })
  stores.push(store)
  await store.load()
  books.forEach((b) => store.create(b))
  return store
}

function lookupOf(over: Partial<Lookup> = {}): Lookup {
  return { lookupIsbn: () => Promise.resolve(null), searchTitle: () => Promise.resolve([]), ...over }
}

beforeEach(() => {
  globalThis.indexedDB = new IDBFactory()
})

afterEach(async () => {
  vi.restoreAllMocks()
  await Promise.all(stores.splice(0).map((s) => s.close()))
})

describe('AddPage', () => {
  it('addingAnExistingBookOpensIt', async () => {
    const user = userEvent.setup()
    const lookup = lookupOf({ lookupIsbn: () => Promise.resolve(found()) })

    // Same ISBN.
    const store = await open([book('a', { isbn: '9789722015981', title: 'Ensaio sobre a Cegueira' })])
    const onOpen = vi.fn()
    const first = render(<AddPage store={store} lookup={lookup} onOpen={onOpen} />)
    await user.type(screen.getByLabelText('ISBN or title'), '9789722015981')
    await user.click(screen.getByRole('button', { name: 'Search' }))
    expect(await screen.findByText('Already in your library')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Open' }))
    expect(onOpen).toHaveBeenCalledWith('a')
    expect(store.books()).toHaveLength(1)
    first.unmount()
    globalThis.indexedDB = new IDBFactory()

    // Same title and author, another ISBN.
    const store2 = await open([book('b', { isbn: '9780140449136', title: 'Blindness: a novel', authors: 'José Saramago' })])
    const onOpen2 = vi.fn()
    render(<AddPage store={store2} lookup={lookup} onOpen={onOpen2} />)
    await user.type(screen.getByLabelText('ISBN or title'), '9789722015981')
    await user.click(screen.getByRole('button', { name: 'Search' }))
    await user.click(await screen.findByRole('button', { name: 'Open' }))
    expect(onOpen2).toHaveBeenCalledWith('b')
    expect(store2.books()).toHaveLength(1)
  })

  it('authorsTakeTheLibrarySpelling', async () => {
    const user = userEvent.setup()
    const store = await open([book('a', { title: 'Other', authors: 'Han Kang' })])
    const lookup = lookupOf({
      searchTitle: () => Promise.resolve([found({ isbn: null, title: 'The Vegetarian', authors: ['Kang Han'] })]),
    })
    render(<AddPage store={store} lookup={lookup} onOpen={vi.fn()} />)
    await user.type(screen.getByLabelText('ISBN or title'), 'vegetarian')
    await user.click(screen.getByRole('button', { name: 'Search' }))
    await user.click(await screen.findByRole('button', { name: 'Add' }))
    const added = store.books().find((b) => b.title === 'The Vegetarian')
    expect(added).toMatchObject({ authors: 'Han Kang', status: 'TO_READ', isbn: null })
    expect(added?.dateAdded).toBeGreaterThan(1_000_000_000_000)
  })

  it('pasteLooksUpAtMostFourAtOnce', async () => {
    const user = userEvent.setup()
    const store = await open()
    const isbns = ['9789722015981', '9780140449136', '9783161484100', '9780306406157', '9780975229002']
    let inFlight = 0
    let peak = 0
    const release: (() => void)[] = []
    const lookup = lookupOf({
      lookupIsbn: (isbn) => {
        inFlight += 1
        peak = Math.max(peak, inFlight)
        return new Promise((resolve) => {
          release.push(() => {
            inFlight -= 1
            resolve(found({ isbn, title: `T${isbn}` }))
          })
        })
      },
    })
    render(<AddPage store={store} lookup={lookup} onOpen={vi.fn()} />)
    await user.type(screen.getByLabelText('ISBNs, one per line'), isbns.join('{Enter}'))
    await user.click(screen.getByRole('button', { name: 'Look up' }))
    await waitFor(() => expect(release).toHaveLength(4))
    expect(peak).toBe(4)
    release.shift()?.()
    await waitFor(() => expect(release).toHaveLength(4))
    expect(peak).toBe(4)
    while (release.length > 0) release.shift()?.()
    expect(await screen.findByRole('button', { name: 'Add 5 books' })).toBeInTheDocument()
  })

  it('pasteAddsOnlyFoundAndNew', async () => {
    const user = userEvent.setup()
    const store = await open([book('a', { isbn: '9780140449136', title: 'The Odyssey', authors: 'Homer' })])
    const lookup = lookupOf({
      lookupIsbn: (isbn) => {
        if (isbn === '9789722015981') return Promise.resolve(found())
        if (isbn === '9780140449136') return Promise.resolve(found({ isbn, title: 'The Odyssey', authors: ['Homer'] }))
        if (isbn === '9783161484100') return Promise.reject(new Error('down'))
        return Promise.resolve(null)
      },
    })
    render(<AddPage store={store} lookup={lookup} onOpen={vi.fn()} />)
    await user.type(
      screen.getByLabelText('ISBNs, one per line'),
      ['9789722015981', '9780140449136', '9780306406157', '9783161484100'].join('{Enter}'),
    )
    await user.click(screen.getByRole('button', { name: 'Look up' }))
    expect(await screen.findByText('Found')).toBeInTheDocument()
    expect(screen.getAllByText('Already in your library')).toHaveLength(1)
    expect(screen.getByText('Not found')).toBeInTheDocument()
    expect(screen.getByText("Couldn't look this up")).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Add 1 book' }))
    expect(store.books().map((b) => b.title).sort()).toEqual(['Blindness', 'The Odyssey'])
    expect(screen.getByText('Added 1 book')).toBeInTheDocument()
  })

  it('showsADescriptionAsTextOnly', async () => {
    const user = userEvent.setup()
    const store = await open()
    const lookup = lookupOf({
      searchTitle: () => Promise.resolve([found({ description: '<img src=x onerror=alert(1)> hello' })]),
    })
    render(<AddPage store={store} lookup={lookup} onOpen={vi.fn()} />)
    await user.type(screen.getByLabelText('ISBN or title'), 'blind')
    await user.click(screen.getByRole('button', { name: 'Search' }))
    expect(await screen.findByText('<img src=x onerror=alert(1)> hello')).toBeInTheDocument()
    expect(document.querySelector('img')).toBeNull()
  })
  it('pasteTreatsTwoSpellingsOfOneIsbnAsOne', async () => {
    const user = userEvent.setup()
    const store = await open()
    const lookup = lookupOf({
      lookupIsbn: (isbn) =>
        Promise.resolve(isbn === '9780140449136' ? found({ isbn, title: 'Blindness: second ed', authors: ['José Saramago'] }) : found()),
    })
    render(<AddPage store={store} lookup={lookup} onOpen={vi.fn()} />)
    await user.type(
      screen.getByLabelText('ISBNs, one per line'),
      ['978-972-20-1598-1', '9789722015981', '9780140449136'].join('{Enter}'),
    )
    await user.click(screen.getByRole('button', { name: 'Look up' }))
    // The third is the same title and author under another ISBN: one book in the batch.
    await user.click(await screen.findByRole('button', { name: 'Add 1 book' }))
    expect(store.books()).toHaveLength(1)
    expect(screen.getByText('Added 1 book')).toBeInTheDocument()
  })

  it('aMissWithAFailedSourceSaysSoOnScreen', async () => {
    const user = userEvent.setup()
    const store = await open()
    const lookup = lookupOf({ lookupIsbn: () => Promise.reject(new NotFoundWithErrors()) })
    render(<AddPage store={store} lookup={lookup} onOpen={vi.fn()} />)
    await user.type(screen.getByLabelText('ISBN or title'), '9789722015981')
    await user.click(screen.getByRole('button', { name: 'Search' }))
    expect(await screen.findByText(/source couldn.t be reached/)).toBeInTheDocument()
    await user.type(screen.getByLabelText('ISBNs, one per line'), '9789722015981')
    await user.click(screen.getByRole('button', { name: 'Look up' }))
    expect(await screen.findAllByText(/source couldn.t be reached/)).toHaveLength(2)
  })
})
