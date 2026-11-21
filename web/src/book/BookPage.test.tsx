import 'fake-indexeddb/auto'
import { IDBFactory } from 'fake-indexeddb'
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
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
  const createSignedUrls = vi.fn(async (paths: string[]) => ({
    data: paths.map((path) => ({ path, signedUrl: `https://signed.example/${path}`, error: null })),
    error: null,
  }))
  const storage: CoverStorage = { from: () => ({ upload, createSignedUrls }) }
  return { storage, upload, createSignedUrls }
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
    const user = userEvent.setup({ delay: null })
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
    const user = userEvent.setup({ delay: null })
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
      ['Lent to', 'Zoe', 'lent_to', 'Zoe'],
      ['Lent on', '04/03/2026', 'lent_on', new Date(2026, 2, 4).getTime()],
      ['Started', '02/01/2026', 'date_started', new Date(2026, 0, 2).getTime()],
      ['Finished', '03/02/2026', 'date_read', new Date(2026, 1, 3).getTime()],
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
    expect(saved.pageCount).toBe(310)
  }, T)

  it('tabbingThroughEveryFieldEditsNothing', async () => {
    const store = await open(book({ title: 'Blindness', tags: ['a'], dateStarted: Date.UTC(2026, 0, 2, 15) }))
    const edit = vi.spyOn(store, 'edit')
    const user = userEvent.setup({ delay: null })
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
    const user = userEvent.setup({ delay: null })
    show(store)

    const same: [string, string][] = [
      // Written out: 23:30 UTC on 2 Jan is 3 Jan in Auckland; the other is local midnight on 5 Mar.
      ['Finished', '03/01/2026'], ['Started', '05/03/2026'], ['Pages', '310'], ['Year', '1995'],
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
    const user = userEvent.setup({ delay: null })
    show(store)

    await user.type(screen.getByLabelText('Started'), '06/03/2026')
    await user.tab()

    expect(edit).toHaveBeenCalledTimes(1)
    expect(edit.mock.calls[0][2]).toEqual({ date_started: new Date(2026, 2, 6).getTime() })
  }, T)

  it('clearingAFieldSavesNullOrEmptyAsTheRowHasIt', async () => {
    const store = await open(book({ publisher: 'Caminho', pageCount: 310, notes: 'x' }))
    const edit = vi.spyOn(store, 'edit')
    const user = userEvent.setup({ delay: null })
    show(store)

    await user.clear(screen.getByLabelText('Publisher'))
    await user.clear(screen.getByLabelText('Pages'))
    await user.clear(screen.getByLabelText('Notes'))
    await user.tab()
    expect(edit.mock.calls.map((c) => c[2])).toEqual([
      { publisher: null }, { page_count: null }, { notes: '' },
    ])
  }, T)

  it('statusAndScoreLikeTheApp', async () => {
    const store = await open()
    const user = userEvent.setup({ delay: null })
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
    const user = userEvent.setup({ delay: null })
    show(store)
    await user.selectOptions(screen.getByLabelText('Status'), 'Read')
    expect(store.book('1')).toMatchObject({ status: 'READ', dateStarted: 100, dateRead: 9_000_000 })
  }, T)

  it('aQuoteOverTheLimitIsNotSaved', async () => {
    const store = await open()
    const user = userEvent.setup({ delay: null })
    show(store)
    const quotes = () => store.quotes('1')

    // Adding: the box stops at the limit, and a longer value that arrives anyway is refused with a message.
    const box = screen.getByLabelText('New quote')
    expect(box).toHaveAttribute('maxlength', '5000')
    fireEvent.change(box, { target: { value: 'x'.repeat(5001) } })
    await user.click(screen.getByRole('button', { name: 'Add quote' }))
    expect(quotes()).toHaveLength(0)
    expect(screen.getByRole('alert')).toHaveTextContent('New quote is too long (5000 characters at most)')

    // Editing: same rule.
    fireEvent.change(box, { target: { value: 'short' } })
    await user.click(screen.getByRole('button', { name: 'Add quote' }))
    expect(quotes()).toHaveLength(1)
    const text = screen.getByLabelText('Quote 1 text')
    expect(text).toHaveAttribute('maxlength', '5000')
    await user.click(text)
    fireEvent.change(text, { target: { value: 'y'.repeat(5001) } })
    await user.tab()
    expect(quotes()[0].text).toBe('short')
  }, T)

  it('aNegativeQuotePageSaysSo', async () => {
    const user = userEvent.setup({ delay: null })
    const store = await open()
    show(store)
    await user.type(screen.getByLabelText('New quote'), 'Rain')
    await user.type(screen.getByLabelText('Quote page'), '-3')
    await user.click(screen.getByRole('button', { name: 'Add quote' }))
    expect(screen.getByRole('alert')).toHaveTextContent('The page must be 0 or more.')
    expect(store.quotes('1')).toHaveLength(0)
    await user.clear(screen.getByLabelText('Quote page'))
    await user.type(screen.getByLabelText('Quote page'), '1.5')
    await user.click(screen.getByRole('button', { name: 'Add quote' }))
    expect(screen.getByRole('alert')).toHaveTextContent('The page must be a whole number.')
  })

  it('quotesAddEditDelete', async () => {
    const store = await open()
    const user = userEvent.setup({ delay: null })
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
    const user = userEvent.setup({ delay: null })
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
    const user = userEvent.setup({ delay: null })
    await user.upload(screen.getByLabelText('Upload cover'), new File(['x'], 'b.png', { type: 'image/png' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('bucket says no')
    expect(store.book('1')!.coverUrl).toBeNull()
  }, T)

  it('aPastedLinkSetsTheCoverAndRemoveClearsIt', async () => {
    const store = await open()
    const user = userEvent.setup({ delay: null })
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
    const { createSignedUrls } = show(store)
    // The generated cover shows until the link arrives.
    await waitFor(() =>
      expect(screen.getByRole('img', { name: 'Cover of Blindness' })).toHaveAttribute('src', 'https://signed.example/u1/1.jpg'),
    )
    expect(createSignedUrls).toHaveBeenCalledWith(['u1/1.jpg'], 3600)
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

  it('addingAQuoteExplainsWhatsMissing', async () => {
    const store = await open()
    const user = userEvent.setup({ delay: null })
    show(store)
    const add = () => user.click(screen.getByRole('button', { name: 'Add quote' }))

    await add()
    expect(screen.getByRole('alert')).toHaveTextContent('Write the quote first.')
    expect(store.quotes('1')).toHaveLength(0)

    await user.type(screen.getByLabelText('New quote'), 'It is raining')
    await user.type(screen.getByLabelText('Quote page'), '1.5')
    await add()
    expect(screen.getByRole('alert')).toHaveTextContent('The page must be a whole number.')
    expect(store.quotes('1')).toHaveLength(0)

    // Fixed, it adds and the message goes.
    await user.clear(screen.getByLabelText('Quote page'))
    await user.type(screen.getByLabelText('Quote page'), '3')
    await add()
    expect(store.quotes('1')).toHaveLength(1)
    expect(screen.queryByRole('alert')).toBeNull()
  }, T)

  it('progressOver100IsRefused', async () => {
    const store = await open(book({ status: 'READING', progressPercent: 40 }))
    const edit = vi.spyOn(store, 'edit')
    const user = userEvent.setup({ delay: null })
    show(store)
    const box = screen.getByLabelText('Progress (%)')
    await user.clear(box)
    await user.type(box, '150')
    await user.tab()
    expect(edit).not.toHaveBeenCalled()
    expect(store.book('1')?.progressPercent).toBe(40)
    expect(screen.getByRole('alert')).toHaveTextContent("Progress (%) can't be more than 100.")

    await user.clear(box)
    await user.type(box, '100')
    await user.tab()
    expect(store.book('1')?.progressPercent).toBe(100)
    expect(screen.queryByRole('alert')).toBeNull()
  }, T)

  it('addingATagChipSavesIt', async () => {
    const store = await open(book({ tags: ['a'] }))
    const edit = vi.spyOn(store, 'edit')
    const user = userEvent.setup({ delay: null })
    show(store)

    const box = screen.getByLabelText('Tags')
    await user.type(box, 'novel{Enter}')
    expect(edit).toHaveBeenLastCalledWith('books', '1', { tags: ['a', 'novel'] })
    expect(box).toHaveValue('')
    // A comma adds one too; a tag already there is not added twice, and nothing is saved for it.
    await user.type(box, 'portugal,')
    expect(edit).toHaveBeenLastCalledWith('books', '1', { tags: ['a', 'novel', 'portugal'] })
    edit.mockClear()
    await user.type(box, 'novel{Enter}')
    expect(edit).not.toHaveBeenCalled()
    expect(store.book('1')?.tags).toEqual(['a', 'novel', 'portugal'])
    expect(screen.getByRole('button', { name: 'Remove tag portugal' })).toBeInTheDocument()

    // Past the server's limit a tag is refused with the usual message.
    fireEvent.change(box, { target: { value: 'x'.repeat(2000) } })
    await user.keyboard('{Enter}')
    expect(edit).not.toHaveBeenCalled()
    expect(screen.getByRole('alert')).toHaveTextContent('Tags is too long (2000 characters at most)')
  }, T)

  it('removingATagChipSavesIt', async () => {
    const store = await open(book({ tags: ['a', 'b', 'c'] }))
    const edit = vi.spyOn(store, 'edit')
    const user = userEvent.setup({ delay: null })
    show(store)

    await user.click(screen.getByRole('button', { name: 'Remove tag b' }))
    expect(edit).toHaveBeenLastCalledWith('books', '1', { tags: ['a', 'c'] })
    expect(screen.queryByRole('button', { name: 'Remove tag b' })).not.toBeInTheDocument()

    // Backspace in the empty box removes the last chip; with text in it, it only edits the text.
    const box = screen.getByLabelText('Tags')
    await user.type(box, 'x{Backspace}')
    expect(edit).toHaveBeenCalledTimes(1)
    await user.keyboard('{Backspace}')
    expect(edit).toHaveBeenLastCalledWith('books', '1', { tags: ['a'] })
    expect(store.book('1')?.tags).toEqual(['a'])
  }, T)

  it('tagSuggestionsComeFromTheLibrary', async () => {
    const store = await open(book({ tags: ['a'] }))
    store.create(book({ id: '2', title: 'Other', tags: ['sci-fi', 'a', 'old'] }))
    show(store)

    const box = screen.getByLabelText('Tags')
    const list = document.getElementById(box.getAttribute('list') ?? 'none')
    expect(list?.tagName).toBe('DATALIST')
    // The library's tags, without the ones this book already has.
    expect(Array.from(list!.querySelectorAll('option')).map((o) => o.getAttribute('value'))).toEqual(['old', 'sci-fi'])
  }, T)

  it('atAGlanceShowsProgressAndDates', async () => {
    const store = await open(book({
      status: 'READING', pageCount: 248, currentPage: 132, progressPercent: 53, tags: ['nobel'],
      dateStarted: new Date(2026, 9, 27).getTime(), lentTo: 'Carla', lentOn: new Date(2026, 2, 3).getTime(),
    }))
    show(store)

    const glance = screen.getByRole('complementary', { name: 'At a glance' })
    expect(within(glance).getByRole('progressbar')).toHaveAttribute('aria-valuenow', '53')
    expect(glance).toHaveTextContent('Page 132 of 248')
    expect(glance).toHaveTextContent('Started27 Oct 2026')
    expect(glance).toHaveTextContent('nobel')
    expect(glance).toHaveTextContent('Lent to Carla on 3 Mar 2026')
    expect(within(glance).getByRole('button', { name: "I'm on page…" })).toBeInTheDocument()
    expect(within(glance).getByRole('button', { name: 'Mark as read' })).toBeInTheDocument()
  }, T)

  it('markAsReadSetsTheStatusAndDate', async () => {
    const store = await open(book({ status: 'READING', dateStarted: 100 }))
    vi.spyOn(Date, 'now').mockReturnValue(9_000_000)
    const user = userEvent.setup({ delay: null })
    show(store)

    await user.click(screen.getByRole('button', { name: 'Mark as read' }))

    expect(store.book('1')).toMatchObject({ status: 'READ', dateStarted: 100, dateRead: 9_000_000 })
    expect(screen.queryByRole('button', { name: 'Mark as read' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: "I'm on page…" })).not.toBeInTheDocument()
  }, T)

  it('imOnPageSavesTheProgress', async () => {
    const store = await open(book({ status: 'READING', pageCount: 200 }))
    const edit = vi.spyOn(store, 'edit')
    const user = userEvent.setup({ delay: null })
    show(store)

    await user.click(screen.getByRole('button', { name: "I'm on page…" }))
    await user.type(screen.getByLabelText('Current page of Blindness'), '50{Enter}')

    // As the app's quick progress: the page, and the percentage that follows from the page count.
    expect(edit).toHaveBeenCalledTimes(1)
    expect(edit).toHaveBeenCalledWith('books', '1', { current_page: 50, progress_percent: 25 })
    expect(screen.queryByLabelText('Current page of Blindness')).not.toBeInTheDocument()
  }, T)

  it('closingTheProgressEditorReturnsFocusToItsButton', async () => {
    const store = await open(book({ status: 'READING', pageCount: 200 }))
    const user = userEvent.setup({ delay: null })
    show(store)

    await user.click(screen.getByRole('button', { name: "I'm on page…" }))
    await user.keyboard('50{Enter}')
    expect(screen.getByRole('button', { name: "I'm on page…" })).toHaveFocus()
  }, T)

  it('aCommittedFieldShowsSaved', async () => {
    const store = await open(book({ publisher: 'Caminho', tags: ['a'] }))
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] })
    try {
      show(store)
      const box = screen.getByLabelText('Publisher')

      // Nothing changed: nothing shown.
      fireEvent.change(box, { target: { value: 'Caminho' } })
      fireEvent.blur(box)
      expect(screen.queryByText('Saved')).not.toBeInTheDocument()

      fireEvent.change(box, { target: { value: 'Dom Quixote' } })
      fireEvent.blur(box)
      expect(screen.getByText('Saved')).toBeInTheDocument()
      expect(screen.getByText('Saved').closest('[role="status"]')).not.toBeNull()
      expect(store.book('1')?.publisher).toBe('Dom Quixote')
      act(() => {
        vi.advanceTimersByTime(2100)
      })
      expect(screen.queryByText('Saved')).not.toBeInTheDocument()

      // A date shows it too; a date retyped as it was does not.
      const started = screen.getByLabelText('Started')
      fireEvent.change(started, { target: { value: '02/01/2026' } })
      fireEvent.blur(started)
      expect(screen.getAllByText('Saved')).toHaveLength(1)
      act(() => {
        vi.advanceTimersByTime(2100)
      })
      fireEvent.change(started, { target: { value: '2/1/26' } })
      fireEvent.blur(started)
      expect(screen.queryByText('Saved')).not.toBeInTheDocument()

      // So does a tag chip.
      fireEvent.change(screen.getByLabelText('Tags'), { target: { value: 'b,' } })
      expect(screen.getAllByText('Saved')).toHaveLength(1)
    } finally {
      vi.useRealTimers()
    }
  }, T)

  it('pastingAPictureUploadsTheCover', async () => {
    const store = await open()
    const { upload, shrink } = show(store)
    const file = new File(['png'], 'cover.png', { type: 'image/png' })

    // Focus in a text box: the paste is the text's, not the cover's.
    fireEvent.paste(screen.getByLabelText('Notes'), { clipboardData: { files: [file], types: ['Files'] } })
    expect(shrink).not.toHaveBeenCalled()

    fireEvent.paste(document.body, { clipboardData: { files: [file], types: ['Files'] } })

    await waitFor(() => expect(store.book('1')!.coverUrl).toBe('photo'))
    expect(shrink).toHaveBeenCalledWith(file)
    expect(upload).toHaveBeenCalledTimes(1)
    expect(screen.getByText('Or drop or paste a picture anywhere on this page.')).toBeInTheDocument()
  }, T)

  it('aQuoteEditThatSavesNothingShowsNoTick', async () => {
    const store = await open()
    store.createQuote({ id: 'q1', bookId: '1', text: 'It is raining', page: 5, dateAdded: 1, position: null, deleted: false })
    show(store)

    // Emptying a quote is refused; a page typed as 05 over 5 and a page that isn't one save nothing.
    const text = screen.getByLabelText('Quote 1 text')
    fireEvent.change(text, { target: { value: '' } })
    fireEvent.blur(text)
    const page = screen.getByLabelText('Quote 1 page')
    fireEvent.change(page, { target: { value: '05' } })
    fireEvent.blur(page)
    expect(store.quotes('1')[0]).toMatchObject({ text: 'It is raining', page: 5 })
    expect(screen.queryByText('Saved')).not.toBeInTheDocument()

    fireEvent.change(page, { target: { value: '6' } })
    fireEvent.blur(page)
    expect(store.quotes('1')[0].page).toBe(6)
    expect(screen.getAllByText('Saved')).toHaveLength(1)
  }, T)

  it('aDateTypoSaysHowToTypeIt', async () => {
    const store = await open(book({ dateStarted: new Date(2026, 0, 2).getTime() }))
    const edit = vi.spyOn(store, 'edit')
    const user = userEvent.setup({ delay: null })
    show(store)

    const box = screen.getByLabelText('Started')
    await user.clear(box)
    await user.type(box, '10/27/2026')
    await user.tab()
    expect(edit).not.toHaveBeenCalled()
    expect(box).toHaveValue('02/01/2026')
    expect(screen.getByRole('alert')).toHaveTextContent('Type a date as dd/mm/yyyy')

    await user.type(box, '3')
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  }, T)

  it('holdingBackspaceRemovesOneChip', async () => {
    const store = await open(book({ tags: ['a', 'b', 'c'] }))
    show(store)
    const box = screen.getByLabelText('Tags')
    box.focus()

    fireEvent.keyDown(box, { key: 'Backspace' })
    fireEvent.keyDown(box, { key: 'Backspace', repeat: true })
    fireEvent.keyDown(box, { key: 'Backspace', repeat: true })
    expect(store.book('1')?.tags).toEqual(['a', 'b'])

    // Enter while an input method is composing is not "add".
    fireEvent.change(box, { target: { value: 'ni' } })
    fireEvent.keyDown(box, { key: 'Enter', isComposing: true })
    expect(store.book('1')?.tags).toEqual(['a', 'b'])
  }, T)

  it('duplicateTagsShowOnceAndRemoveTogether', async () => {
    const store = await open(book({ tags: ['a', 'b', 'a'] }))
    const edit = vi.spyOn(store, 'edit')
    const user = userEvent.setup({ delay: null })
    show(store)

    expect(screen.getAllByRole('button', { name: 'Remove tag a' })).toHaveLength(1)
    await user.click(screen.getByRole('button', { name: 'Remove tag a' }))
    expect(edit).toHaveBeenLastCalledWith('books', '1', { tags: ['b'] })
  }, T)

  it('theTooLongAlertClearsWhenEditing', async () => {
    const store = await open(book({ tags: ['a'] }))
    const user = userEvent.setup({ delay: null })
    show(store)

    const box = screen.getByLabelText('Tags')
    await user.click(box)
    fireEvent.change(box, { target: { value: 'x'.repeat(2000) } })
    await user.keyboard('{Enter}')
    expect(screen.getByRole('alert')).toBeInTheDocument()
    await user.type(box, 'y')
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()

    await user.keyboard('{Enter}')
    expect(screen.getByRole('alert')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Remove tag a' }))
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  }, T)

  it('markAsReadWorksWhileThePageEditorIsOpen', async () => {
    const store = await open(book({ status: 'READING', pageCount: 200 }))
    const user = userEvent.setup({ delay: null })
    show(store)

    await user.click(screen.getByRole('button', { name: "I'm on page…" }))
    const editor = screen.getByLabelText('Current page of Blindness')
    const mark = screen.getByRole('button', { name: 'Mark as read' })
    // The editor sits after the buttons, so closing it on blur moves nothing under the pointer.
    expect(mark.compareDocumentPosition(editor) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    await user.click(mark)

    expect(store.book('1')?.status).toBe('READ')
    expect(screen.queryByLabelText('Current page of Blindness')).not.toBeInTheDocument()

    // Leaving READING by the status box closes an open editor too.
    await user.selectOptions(screen.getByLabelText('Status'), 'READING')
    expect(screen.queryByLabelText('Current page of Blindness')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: "I'm on page…" })).toBeInTheDocument()
  }, T)
})
