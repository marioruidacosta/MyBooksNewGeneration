import 'fake-indexeddb/auto'
import { IDBFactory } from 'fake-indexeddb'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { CoverStorage } from '../covers/storage'
import { rowKey, type SyncApi } from '../sync/api'
import type { Book, SyncRow } from '../sync/model'
import { LibraryStore } from '../sync/store'
import { Publisher } from '../share/publisher'
import { SharingContext } from '../share/SharingContext'
import { BookPage } from './BookPage'

const T = 15_000

function makeApi(rows: SyncRow[] = []): SyncApi {
  return {
    pull: async (after) => (after === 0 ? { rows, last: rows.length } : { rows: [], last: after }),
    push: async (changes) => ({ applied: changes.map((c) => rowKey(c.table, c.id)), rejected: [] }),
    onPing: () => () => {},
  }
}

function book(over: Partial<Book> = {}): Book {
  return {
    id: '1', isbn: null, title: 'Blindness', authors: 'José Saramago', publisher: null, publishedYear: null,
    pageCount: null, coverUrl: null, description: null, status: 'TO_READ', rating: null, notes: '', dateAdded: 7,
    dateRead: null, dateStarted: null, currentPage: null, progressPercent: null, review: '', tags: [], series: null,
    seriesNumber: null, lentTo: null, lentOn: null, deleted: false, ...over,
  }
}

const stores: LibraryStore[] = []

async function open(b: Book = book(), rows: SyncRow[] = []) {
  const store = new LibraryStore({ api: makeApi(rows), owner: 'u1', retryDelays: [60_000], pollMs: 0 })
  stores.push(store)
  await store.load()
  store.create(b)
  return store
}

function fakeStorage() {
  const upload = vi.fn(async () => ({ error: null as { message: string } | null }))
  const createSignedUrl = vi.fn(async (path: string) => ({
    data: { signedUrl: `https://signed.example/${path}` },
    error: null,
  }))
  const storage: CoverStorage = { from: () => ({ upload, createSignedUrl }) }
  return { storage, upload, createSignedUrl }
}

const jpeg = () => new Blob(['jpeg'], { type: 'image/jpeg' })

function show(store: LibraryStore, extra: { shrink?: (f: Blob) => Promise<Blob> } = {}) {
  const fake = fakeStorage()
  const shrink = extra.shrink ?? vi.fn(async () => jpeg())
  render(<BookPage store={store} owner="u1" id="1" storage={fake.storage} shrink={shrink} onBack={() => {}} />)
  return { ...fake, shrink }
}

beforeEach(() => {
  globalThis.indexedDB = new IDBFactory()
})

afterEach(async () => {
  vi.restoreAllMocks()
  await Promise.all(stores.splice(0).map((s) => s.close()))
})

describe('BookPage', () => {
  it('aReviewOverTheLimitIsNotSaved', async () => {
    const store = await open(book({ review: 'fine' }))
    const edit = vi.spyOn(store, 'edit')
    const user = userEvent.setup()
    show(store)

    const box = screen.getByLabelText('Review')
    expect(box).toHaveAttribute('maxlength', '2000')
    expect(screen.getByLabelText('Title')).toHaveAttribute('maxlength', '300')
    // A value that arrives longer than the box allows (not typed) is refused with a short message.
    await user.click(box)
    fireEvent.change(box, { target: { value: 'x'.repeat(2001) } })
    await user.tab()
    expect(edit).not.toHaveBeenCalled()
    expect(store.book('1')?.review).toBe('fine')
    expect(screen.getByRole('alert')).toHaveTextContent('Review is too long (2000 characters at most)')

    // A value within the limit saves.
    await user.click(box)
    fireEvent.change(box, { target: { value: 'x'.repeat(2000) } })
    await user.tab()
    expect(store.book('1')?.review).toBe('x'.repeat(2000))
  }, T)

  it('everyFieldSavesOnBlur', async () => {
    const store = await open()
    const edit = vi.spyOn(store, 'edit')
    const user = userEvent.setup()
    show(store)

    const cases: [label: string, typed: string, field: string, value: unknown][] = [
      ['Title', ' Ensaio', 'title', 'Blindness Ensaio'],
      ['Authors', ' & Co', 'authors', 'José Saramago & Co'],
      ['ISBN', '9789722015981', 'isbn', '9789722015981'],
      ['Publisher', 'Caminho', 'publisher', 'Caminho'],
      ['Year', '1995', 'published_year', 1995],
      ['Pages', '310', 'page_count', 310],
      ['Series', 'Saga', 'series', 'Saga'],
      ['Series number', '2', 'series_number', '2'],
      ['Description', 'A plague of white', 'description', 'A plague of white'],
      ['Notes', 'my note', 'notes', 'my note'],
      ['Review', 'great', 'review', 'great'],
      ['Tags', 'novel, portugal', 'tags', ['novel', 'portugal']],
      ['Lent to', 'Zoe', 'lent_to', 'Zoe'],
      ['Lent on', '2026-03-04', 'lent_on', new Date(2026, 2, 4).getTime()],
      ['Started', '2026-01-02', 'date_started', new Date(2026, 0, 2).getTime()],
      ['Finished', '2026-02-03', 'date_read', new Date(2026, 1, 3).getTime()],
      ['Current page', '12', 'current_page', 12],
      ['Progress (%)', '40', 'progress_percent', 40],
    ]
    for (const [label, typed, field, value] of cases) {
      edit.mockClear()
      await user.type(screen.getByLabelText(label), typed)
      await user.tab()
      expect(edit, label).toHaveBeenCalledTimes(1)
      expect(edit.mock.calls[0][2], label).toEqual({ [field]: value })
    }
    const saved = store.book('1')!
    expect(saved.title).toBe('Blindness Ensaio')
    expect(saved.tags).toEqual(['novel', 'portugal'])
    expect(saved.pageCount).toBe(310)
  }, T)

  it('tabbingThroughEveryFieldEditsNothing', async () => {
    const store = await open(book({ title: 'Blindness', tags: ['a'], dateStarted: Date.UTC(2026, 0, 2, 15) }))
    const edit = vi.spyOn(store, 'edit')
    const user = userEvent.setup()
    show(store)

    await user.click(screen.getByLabelText('Title'))
    for (let i = 0; i < 40; i++) await user.tab()
    expect(edit).not.toHaveBeenCalled()
  }, T)

  it('retypingTheSameValueEditsNothing', async () => {
    // 23:30 UTC is another local day east of Greenwich; a local midnight is the day before in UTC west of it.
    const read = Date.UTC(2026, 0, 2, 23, 30)
    const started = new Date(2026, 2, 5, 0, 0).getTime()
    const store = await open(book({ dateRead: read, dateStarted: started, pageCount: 310, publishedYear: 1995, tags: ['a', 'b'] }))
    const edit = vi.spyOn(store, 'edit')
    const user = userEvent.setup()
    show(store)

    const same: [string, string][] = [
      // Written out: 23:30 UTC on 2 Jan is 3 Jan in Auckland; the other is local midnight on 5 Mar.
      ['Finished', '2026-01-03'], ['Started', '2026-03-05'], ['Pages', '310'], ['Year', '1995'], ['Tags', 'a, b'],
    ]
    for (const [label, text] of same) {
      const box = screen.getByLabelText(label)
      // The page shows exactly this text to begin with.
      expect(box, label).toHaveValue(label === 'Pages' || label === 'Year' ? Number(text) : text)
      await user.clear(box)
      await user.type(box, text)
      await user.tab()
    }
    expect(edit).not.toHaveBeenCalled()
  }, T)

  it('typingANewDateStoresLocalMidnight', async () => {
    const store = await open()
    const edit = vi.spyOn(store, 'edit')
    const user = userEvent.setup()
    show(store)

    await user.type(screen.getByLabelText('Started'), '2026-03-06')
    await user.tab()

    expect(edit).toHaveBeenCalledTimes(1)
    expect(edit.mock.calls[0][2]).toEqual({ date_started: new Date(2026, 2, 6).getTime() })
  }, T)

  it('clearingAFieldSavesNullOrEmptyAsTheRowHasIt', async () => {
    const store = await open(book({ publisher: 'Caminho', pageCount: 310, notes: 'x', tags: ['a'] }))
    const edit = vi.spyOn(store, 'edit')
    const user = userEvent.setup()
    show(store)

    await user.clear(screen.getByLabelText('Publisher'))
    await user.clear(screen.getByLabelText('Pages'))
    await user.clear(screen.getByLabelText('Notes'))
    await user.clear(screen.getByLabelText('Tags'))
    await user.tab()
    expect(edit.mock.calls.map((c) => c[2])).toEqual([
      { publisher: null }, { page_count: null }, { notes: '' }, { tags: [] },
    ])
  }, T)

  it('statusAndScoreLikeTheApp', async () => {
    const store = await open()
    const user = userEvent.setup()
    vi.spyOn(Date, 'now').mockReturnValue(5_000_000)
    show(store)

    const status = screen.getByLabelText('Status')
    expect(within(status).getAllByRole('option').map((o) => o.textContent)).toEqual([
      'Reading', 'To read', 'Read', 'To buy',
    ])

    await user.selectOptions(status, 'Reading')
    expect(store.book('1')).toMatchObject({ status: 'READING', dateStarted: 5_000_000, dateRead: null })

    vi.spyOn(Date, 'now').mockReturnValue(6_000_000)
    await user.selectOptions(status, 'Read')
    expect(store.book('1')).toMatchObject({ status: 'READ', dateStarted: 5_000_000, dateRead: 6_000_000 })

    // A date already there is kept.
    await user.selectOptions(status, 'To buy')
    expect(store.book('1')).toMatchObject({ status: 'WISHLIST', dateRead: null, dateStarted: null })

    // Score: tap a star to set it, the same star again to clear it.
    await user.click(screen.getByRole('button', { name: '4 stars' }))
    expect(store.book('1')!.rating).toBe(4)
    expect(screen.getByRole('button', { name: '4 stars' })).toHaveAttribute('aria-pressed', 'true')
    await user.click(screen.getByRole('button', { name: '2 stars' }))
    expect(store.book('1')!.rating).toBe(2)
    await user.click(screen.getByRole('button', { name: '2 stars' }))
    expect(store.book('1')!.rating).toBeNull()
  }, T)

  it('aFinishedDateAlreadyThereIsKept', async () => {
    const store = await open(book({ status: 'READING', dateStarted: 100 }))
    vi.spyOn(Date, 'now').mockReturnValue(9_000_000)
    const user = userEvent.setup()
    show(store)
    await user.selectOptions(screen.getByLabelText('Status'), 'Read')
    expect(store.book('1')).toMatchObject({ status: 'READ', dateStarted: 100, dateRead: 9_000_000 })
  }, T)

  it('quotesAddEditDelete', async () => {
    const store = await open()
    const user = userEvent.setup()
    show(store)
    const quotes = () => store.quotes('1')

    await user.type(screen.getByLabelText('New quote'), 'It is raining')
    await user.type(screen.getByLabelText('Quote page'), '12')
    await user.click(screen.getByRole('button', { name: 'Add quote' }))
    expect(quotes()).toHaveLength(1)
    expect(quotes()[0]).toMatchObject({ bookId: '1', text: 'It is raining', page: 12 })
    expect(screen.getByLabelText('New quote')).toHaveValue('')

    // An empty one isn't added.
    await user.click(screen.getByRole('button', { name: 'Add quote' }))
    expect(quotes()).toHaveLength(1)

    const edit = vi.spyOn(store, 'edit')
    const text = screen.getByLabelText('Quote 1 text')
    await user.click(text)
    await user.tab()
    expect(edit).not.toHaveBeenCalled()
    await user.type(text, ' hard')
    await user.tab()
    expect(quotes()[0].text).toBe('It is raining hard')
    await user.clear(screen.getByLabelText('Quote 1 page'))
    await user.tab()
    expect(quotes()[0].page).toBeNull()

    await user.click(screen.getByRole('button', { name: 'Delete quote 1' }))
    expect(quotes()).toHaveLength(0)
    expect(screen.queryByLabelText('Quote 1 text')).not.toBeInTheDocument()
  }, T)

  it('droppingAnImageUploadsAndMarksPhoto', async () => {
    const store = await open()
    const { upload, shrink } = show(store)
    const file = new File(['png'], 'cover.png', { type: 'image/png' })

    fireEvent.drop(screen.getByRole('main'), { dataTransfer: { files: [file], types: ['Files'] } })

    await waitFor(() => expect(store.book('1')!.coverUrl).toBe('photo'))
    expect(shrink).toHaveBeenCalledWith(file)
    expect(upload).toHaveBeenCalledTimes(1)
    expect(upload.mock.calls[0]).toEqual([
      'u1/1.jpg', expect.any(Blob), { upsert: true, contentType: 'image/jpeg' },
    ])
  }, T)

  it('aSecondUploadRestampsCoverUrl', async () => {
    const store = await open(book({ coverUrl: 'photo' }))
    const changes: string[] = []
    store.onLocalChange((table, before, after) => {
      if (table === 'books' && before && after && before.coverUrl === after.coverUrl) changes.push('cover re-stamped')
    })
    const edit = vi.spyOn(store, 'edit')
    const user = userEvent.setup()
    const { upload } = show(store)

    await user.upload(screen.getByLabelText('Upload cover'), new File(['x'], 'b.png', { type: 'image/png' }))
    await waitFor(() => expect(upload).toHaveBeenCalledTimes(1))
    await waitFor(() => expect(changes).toHaveLength(1))
    expect(edit).toHaveBeenCalledWith('books', '1', { cover_url: 'photo' }, { restamp: ['cover_url'] })

    await user.upload(screen.getByLabelText('Upload cover'), new File(['y'], 'c.png', { type: 'image/png' }))
    await waitFor(() => expect(changes).toHaveLength(2))
    expect(store.book('1')!.coverUrl).toBe('photo')
  }, T)

  it('aFailedUploadIsShownAndChangesNothing', async () => {
    const store = await open()
    const { upload } = show(store, { shrink: async () => jpeg() })
    upload.mockResolvedValueOnce({ error: { message: 'bucket says no' } })
    const user = userEvent.setup()
    await user.upload(screen.getByLabelText('Upload cover'), new File(['x'], 'b.png', { type: 'image/png' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('bucket says no')
    expect(store.book('1')!.coverUrl).toBeNull()
  }, T)

  it('aPastedLinkSetsTheCoverAndRemoveClearsIt', async () => {
    const store = await open()
    const user = userEvent.setup()
    show(store)

    await user.type(screen.getByLabelText('Cover link'), 'https://example.com/c.jpg')
    await user.click(screen.getByRole('button', { name: 'Use link' }))
    expect(store.book('1')!.coverUrl).toBe('https://example.com/c.jpg')
    expect(screen.getByRole('img', { name: 'Cover of Blindness' })).toHaveAttribute('src', 'https://example.com/c.jpg')

    await user.click(screen.getByRole('button', { name: 'Remove cover' }))
    expect(store.book('1')!.coverUrl).toBeNull()
  }, T)

  it('aPhotoIsShownThroughASignedLink', async () => {
    const store = await open(book({ coverUrl: 'photo' }))
    const { createSignedUrl } = show(store)
    // The generated cover shows until the link arrives.
    await waitFor(() =>
      expect(screen.getByRole('img', { name: 'Cover of Blindness' })).toHaveAttribute('src', 'https://signed.example/u1/1.jpg'),
    )
    expect(createSignedUrl).toHaveBeenCalledWith('u1/1.jpg', 3600)
  }, T)

  it('ebookOnAPhoneIsShownReadOnly', async () => {
    const rows: SyncRow[] = [
      {
        table: 'ebooks', id: '1', seq: 1, times: { format: 5 },
        fields: { format: 'EPUB', file_name: 'blindness.epub', size: 1234, deleted: false },
      },
    ]
    const store = await open(book(), rows)
    show(store)
    const ebook = screen.getByText(/Ebook on a phone/)
    expect(ebook.parentElement).toHaveTextContent('blindness.epub')
    expect(ebook.parentElement?.querySelector('input, textarea, button')).toBeNull()
  }, T)

  it('noEbookLineWithoutAnEbookRecord', async () => {
    const store = await open()
    show(store)
    expect(screen.queryByText(/Ebook on a phone/)).not.toBeInTheDocument()
  }, T)

  it('aMissingBookSaysSo', async () => {
    const store = await open()
    render(<BookPage store={store} owner="u1" id="nope" storage={fakeStorage().storage} onBack={() => {}} />)
    expect(screen.getByText(/isn.t in your library/)).toBeInTheDocument()
  }, T)

  it('saysWhetherTheBookIsSharedWithFriends', async () => {
    const store = await open()
    const shared = ['1']
    const client = { rpc: async () => ({ data: shared, error: null }) }
    const publisher = new Publisher({ store, client, debounceMs: 0, owner: 'u1', currentOwner: () => 'u1' })
    await publisher.start()
    const fake = fakeStorage()
    const { unmount } = render(
      <SharingContext.Provider value={publisher}>
        <BookPage store={store} owner="u1" id="1" storage={fake.storage} onBack={() => {}} />
      </SharingContext.Provider>,
    )
    expect(screen.getByText('Shared with friends')).toBeInTheDocument()
    expect(screen.queryByRole('checkbox', { name: /shared/i })).not.toBeInTheDocument()
    unmount()
    publisher.stop()
    shared.length = 0
    const second = new Publisher({ store, client, debounceMs: 0, owner: 'u1', currentOwner: () => 'u1' })
    await second.start()
    render(
      <SharingContext.Provider value={second}>
        <BookPage store={store} owner="u1" id="1" storage={fake.storage} onBack={() => {}} />
      </SharingContext.Provider>,
    )
    expect(screen.getByText('Not shared')).toBeInTheDocument()
    second.stop()
  }, T)
})
