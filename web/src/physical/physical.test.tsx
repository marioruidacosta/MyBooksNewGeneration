import { useSyncExternalStore } from 'react'
import 'fake-indexeddb/auto'
import { IDBFactory } from 'fake-indexeddb'
import { act, fireEvent, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { rowKey, type SyncApi } from '../sync/api'
import type { Book } from '../sync/model'
import { LibraryStore } from '../sync/store'
import { ToastContext } from '../ui/toastContext'
import { BookcaseDrawing } from './BookcaseDrawing'
import { BookcaseEditPage } from './BookcaseEditPage'
import { BookcasePage } from './BookcasePage'
import { BOOKCASE_PREFIX, toStored, type Bookcase } from './model'
import { PhysicalLibraryPage } from './PhysicalLibraryPage'
import { ShelfPage } from './ShelfPage'
import { WithoutPlacePage } from './WithoutPlacePage'
import { WhereItIs } from './WhereItIs'
import { BookPage } from '../book/BookPage'
import { bookcasesOf } from './places'

const api: SyncApi = {
  pull: async () => ({ rows: [], last: 0 }),
  push: async (changes) => ({ applied: changes.map((c) => rowKey(c.table, c.id)), rejected: [] }),
  onPing: () => () => {},
}

const windowCase: Bookcase = {
  id: 'w', name: 'By the window', room: 'Living room', readOnly: false,
  columns: [{ id: 'c1', shelves: ['s1', 's2'] }, { id: 'c2', shelves: ['s3'] }],
}
const tall: Bookcase = {
  id: 'n', name: 'Tall', room: 'Office', readOnly: false,
  columns: [{ id: 'a', shelves: ['a1', 'a2', 'a3', 'a4'] }, { id: 'b', shelves: ['b1', 'b2', 'b3', 'b4'] }],
}

const book = (id: string, over: Partial<Book> = {}): Book => ({
  id, isbn: null, title: id, authors: 'Someone', publisher: null, publishedYear: null, pageCount: null, coverUrl: null,
  description: null, status: 'READ', rating: null, notes: '', dateAdded: 1, dateRead: null, dateStarted: null, currentPage: null,
  progressPercent: null, review: '', tags: [], series: null, seriesNumber: null, lentTo: null, lentOn: null, shelfId: null,
  shelfPosition: null, deleted: false, ...over,
})

let store: LibraryStore
const saved = (c: Bookcase) => store.setPref(BOOKCASE_PREFIX + c.id, toStored(c))

beforeEach(async () => {
  globalThis.indexedDB = new IDBFactory()
  store = new LibraryStore({ api, owner: 'me', pollMs: 0, retryDelays: [60_000] })
  await store.load()
})
afterEach(async () => {
  await store.close()
})

const home = () => render(<PhysicalLibraryPage store={store} onOpenBook={() => {}} onOpenBookcase={() => {}} onAdd={() => {}} onOpenUnplaced={() => {}} />)

describe('your home', () => {
  it('emptyHomeInvitesABookcase', () => {
    home()
    expect(screen.getByRole('heading', { name: 'Physical library' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Add a bookcase' })).toBeInTheDocument()
  })

  it('roomsListTheirBookcases', () => {
    saved(windowCase)
    store.create(book('on', { shelfId: 's1', shelfPosition: 1 }))
    store.create(book('other', { shelfId: 's3', shelfPosition: 1 }))
    store.create(book('waiting'))
    home()
    expect(screen.getByRole('heading', { name: 'Living room' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /By the window/ })).toHaveTextContent('3 shelves · 2 books')
    expect(screen.getByRole('button', { name: /1 book without a place/ })).toBeInTheDocument()
  })

  it('aRoomCanBeRenamed', async () => {
    const user = userEvent.setup()
    saved(windowCase)
    saved({ ...tall })
    home()
    await user.click(screen.getByRole('button', { name: 'Rename room Living room' }))
    await user.type(screen.getByLabelText('Room name'), ' Lounge ')
    await user.click(screen.getByRole('button', { name: 'Rename' }))
    expect(bookcasesOf(store).find((b) => b.id === 'w')?.room).toBe('Lounge')
    expect(bookcasesOf(store).find((b) => b.id === 'n')?.room).toBe('Office')
  })

  it('aNewerBookcaseIsNotRewrittenByARename', async () => {
    const user = userEvent.setup()
    const newer = '{"v":2,"name":"Newer","room":"Living room","columns":[{"id":"c","shelves":["s"]}]}'
    store.setPref(BOOKCASE_PREFIX + 'r', newer)
    saved(windowCase)
    home()
    await user.click(screen.getByRole('button', { name: 'Rename room Living room' }))
    await user.type(screen.getByLabelText('Room name'), 'Lounge')
    await user.click(screen.getByRole('button', { name: 'Rename' }))
    expect(store.pref(BOOKCASE_PREFIX + 'r')).toBe(newer)
  })
})

describe('the bookcase editor', () => {
  const edit = (id: string | null, onDone = vi.fn()) => {
    render(<BookcaseEditPage store={store} id={id} onDone={onDone} />)
    return onDone
  }

  it('editorDrawsWhatItWillSave', async () => {
    const user = userEvent.setup()
    const done = edit(null)
    await user.type(screen.getByLabelText('Name'), 'Tall bookcase')
    await user.type(screen.getByLabelText('Room'), 'Office')
    expect(screen.getByRole('img', { name: 'Shelf B4, 0 books' })).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Save bookcase' }))
    expect(done).toHaveBeenCalled()
    const c = bookcasesOf(store)[0]
    expect([c.name, c.room, c.columns.map((x) => x.shelves.length)]).toEqual(['Tall bookcase', 'Office', [4, 4]])
  })

  it('theSteppersChangeTheDrawing', async () => {
    const user = userEvent.setup()
    edit(null)
    await user.click(screen.getByRole('button', { name: 'More columns' }))
    await user.click(screen.getByRole('button', { name: 'Fewer shelves' }))
    expect(screen.getByRole('img', { name: 'Shelf C3, 0 books' })).toBeInTheDocument()
    expect(screen.queryByRole('img', { name: 'Shelf C4, 0 books' })).not.toBeInTheDocument()
  })

  it('saveNeedsANameAndARoom', async () => {
    const user = userEvent.setup()
    edit(null)
    const save = screen.getByRole('button', { name: 'Save bookcase' })
    expect(save).toBeDisabled()
    await user.type(screen.getByLabelText('Name'), 'Tall')
    expect(save).toBeDisabled()
    await user.type(screen.getByLabelText('Room'), 'Office')
    expect(save).toBeEnabled()
  })

  it('editingChangesOneColumn', async () => {
    const user = userEvent.setup()
    saved(tall)
    edit('n')
    await user.click(screen.getByRole('button', { name: 'Add a shelf to column B' }))
    await user.click(screen.getByRole('button', { name: 'Save bookcase' }))
    expect(bookcasesOf(store)[0].columns.map((c) => c.shelves.length)).toEqual([4, 5])
  })

  it('removingAShelfWithBooksAsksFirst', async () => {
    const user = userEvent.setup()
    saved(tall)
    store.create(book('one', { shelfId: 'a4', shelfPosition: 1 }))
    store.create(book('two', { shelfId: 'a4', shelfPosition: 2 }))
    edit('n')
    await user.click(screen.getByRole('button', { name: 'Remove a shelf from column A' }))
    const dialog = await screen.findByRole('dialog')
    expect(dialog).toHaveTextContent('2 books will go to Without a place.')
    expect(store.book('one')?.shelfId).toBe('a4')
    await user.click(within(dialog).getByRole('button', { name: 'Remove shelf' }))
    await user.click(screen.getByRole('button', { name: 'Save bookcase' }))
    expect(bookcasesOf(store)[0].columns[0].shelves).toHaveLength(3)
    expect(store.book('one')).toMatchObject({ shelfId: null, title: 'one' })
  })

  it('leavingWithoutSavingKeepsTheBooksOnTheirShelf', async () => {
    const user = userEvent.setup()
    saved(tall)
    store.create(book('one', { shelfId: 'a4', shelfPosition: 1 }))
    edit('n')
    await user.click(screen.getByRole('button', { name: 'Remove a shelf from column A' }))
    await user.click(within(await screen.findByRole('dialog')).getByRole('button', { name: 'Remove shelf' }))
    await user.click(screen.getByRole('button', { name: 'Back' }))
    expect(store.book('one')?.shelfId).toBe('a4')
    expect(bookcasesOf(store)[0].columns[0].shelves).toHaveLength(4)
  })

  it('deletingABookcaseAsksFirstAndKeepsTheBooks', async () => {
    const user = userEvent.setup()
    saved(windowCase)
    store.create(book('one', { shelfId: 's1', shelfPosition: 1 }))
    const done = edit('w')
    await user.click(screen.getByRole('button', { name: 'Delete bookcase' }))
    const dialog = await screen.findByRole('dialog')
    expect(dialog).toHaveTextContent('1 book will go to Without a place.')
    await user.click(within(dialog).getByRole('button', { name: 'Delete' }))
    await vi.waitFor(() => expect(done).toHaveBeenCalled())
    expect(bookcasesOf(store)).toEqual([])
    expect(store.book('one')).toMatchObject({ title: 'one', shelfId: null })
  })

  it('aReadOnlyBookcaseCantBeEdited', () => {
    store.setPref(BOOKCASE_PREFIX + 'r', '{"v":2,"name":"Newer","room":"Office","columns":[{"id":"c","shelves":["s"]}]}')
    edit('r')
    expect(screen.getByText('Made by a newer version of MyBooks. Update the app to change it.')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Save bookcase' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Delete bookcase' })).not.toBeInTheDocument()
  })
})

describe('the drawing', () => {
  it('aFullShelfShowsHowManyMore', () => {
    const books = Array.from({ length: 80 }, (_, i) => book(`b${String(i)}`, { shelfId: 's1', shelfPosition: i }))
    render(<BookcaseDrawing bookcase={windowCase} books={books} labels />)
    expect(screen.getByRole('img', { name: /Shelf A1, 80 books, \d+ not shown/ })).toHaveTextContent(/\+\d+/)
  })

  it('aFoundBookIsAlwaysDrawn', () => {
    const books = Array.from({ length: 80 }, (_, i) => book(`b${String(i)}`, { shelfId: 's1', shelfPosition: i }))
    const { container } = render(<BookcaseDrawing bookcase={windowCase} books={books} highlightShelf="s1" highlightBook="b79" />)
    expect(container.querySelector('.spine--found')).toHaveTextContent('b79')
  })

  it('aShelfWithAnActionIsAButton', async () => {
    const user = userEvent.setup()
    const open = vi.fn()
    render(<BookcaseDrawing bookcase={windowCase} books={[]} labels onShelf={open} />)
    await user.click(screen.getByRole('button', { name: 'Shelf A2, 0 books' }))
    expect(open).toHaveBeenCalledWith('s2')
  })
})

describe('inside a bookcase', () => {
  const toast = { show: vi.fn() }
  beforeEach(() => toast.show.mockClear())
  const shelf = (id = 's1', onOpenBook: (id: string) => void = () => {}) => render(
    <ToastContext.Provider value={toast}>
      <ShelfPage store={store} shelfId={id} onBack={() => {}} onOpenBook={onOpenBook} />
    </ToastContext.Provider>,
  )
  const order = () => screen.getAllByRole('listitem').map((li) => li.querySelector('strong')?.textContent)

  it('tappingAShelfOpensIt', async () => {
    const user = userEvent.setup()
    saved(windowCase)
    const open = vi.fn()
    render(<BookcasePage store={store} id="w" onBack={() => {}} onEdit={() => {}} onOpenShelf={open} />)
    expect(screen.getByRole('heading', { name: 'By the window' })).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Shelf A2, 0 books' }))
    expect(open).toHaveBeenCalledWith('s2')
  })

  it('aRemovedBookcaseLeavesItsScreen', () => {
    const back = vi.fn()
    render(<BookcasePage store={store} id="gone" onBack={back} onEdit={() => {}} onOpenShelf={() => {}} />)
    expect(back).toHaveBeenCalled()
  })

  it('aShelfListsBooksInOrder', () => {
    saved(windowCase)
    store.create(book('Second', { shelfId: 's1', shelfPosition: 2 }))
    store.create(book('First', { shelfId: 's1', shelfPosition: 1, lentTo: 'Ana', lentOn: 1 }))
    shelf()
    expect(screen.getByRole('heading', { name: 'Shelf A1' })).toBeInTheDocument()
    expect(order()).toEqual(['First', 'Second'])
    expect(screen.getByText(/Lent to Ana/)).toBeInTheDocument()
  })

  it('moveDownChangesTheOrder', async () => {
    const user = userEvent.setup()
    saved(windowCase)
    store.create(book('Alpha', { shelfId: 's1', shelfPosition: 1 }))
    store.create(book('Beta', { shelfId: 's1', shelfPosition: 2 }))
    shelf()
    await user.click(screen.getByRole('button', { name: 'Move Alpha down' }))
    expect(order()).toEqual(['Beta', 'Alpha'])
    expect(screen.getByRole('button', { name: 'Move Beta up' })).toBeDisabled()
  })

  it('draggingTheHandleMovesTheBook', () => {
    saved(windowCase)
    for (const [i, t] of ['Alpha', 'Beta', 'Gamma'].entries()) store.create(book(t, { shelfId: 's1', shelfPosition: i + 1 }))
    const { container } = shelf()
    const rows = container.querySelectorAll<HTMLElement>('.place-row')
    Object.defineProperty(rows[0], 'offsetHeight', { value: 50 })
    const handle = rows[0].querySelector('.place-row__handle') as HTMLElement
    // jsdom has no PointerEvent, and fireEvent's fallback drops clientY.
    const pointer = (type: string, clientY: number) => fireEvent(handle, new MouseEvent(type, { bubbles: true, clientY }))
    pointer('pointerdown', 100)
    pointer('pointermove', 200)
    pointer('pointerup', 200)
    expect(order()).toEqual(['Beta', 'Gamma', 'Alpha'])
  })

  it('takingABookOffSendsItToWithoutAPlace', async () => {
    const user = userEvent.setup()
    saved(windowCase)
    store.create(book('Alpha', { shelfId: 's1', shelfPosition: 3 }))
    shelf()
    await user.click(screen.getByRole('button', { name: 'Take Alpha off this shelf' }))
    expect(store.book('Alpha')?.shelfId).toBeNull()
    const undo = toast.show.mock.calls[0][1] as { run: () => void }
    act(() => undo.run())
    expect(store.book('Alpha')).toMatchObject({ shelfId: 's1', shelfPosition: 3 })
  })

  it('pickSearchesAndPlacesWithUndo', async () => {
    const user = userEvent.setup()
    saved(windowCase)
    store.create(book('Stoner'))
    store.create(book('Stone Junction', { shelfId: 's2', shelfPosition: 4 }))
    store.create(book('Emma'))
    shelf()
    await user.click(screen.getByRole('button', { name: 'Pick from library' }))
    const dialog = screen.getByRole('dialog')
    await user.type(within(dialog).getByLabelText('Search your books'), 'stone')
    expect(within(dialog).queryByText('Emma')).not.toBeInTheDocument()
    expect(within(dialog).getByText(/Living room › By the window › Shelf A2/)).toBeInTheDocument()
    await user.click(within(dialog).getByRole('checkbox', { name: /^Stoner/ }))
    await user.click(within(dialog).getByRole('checkbox', { name: /^Stone Junction/ }))
    await user.click(within(dialog).getByRole('button', { name: 'Put 2 on this shelf' }))
    expect(order()).toEqual(['Stoner', 'Stone Junction'])
    const undo = toast.show.mock.calls[0][1] as { label: string; run: () => void }
    expect(undo.label).toBe('Undo')
    act(() => undo.run())
    expect(store.book('Stoner')?.shelfId).toBeNull()
    expect(store.book('Stone Junction')).toMatchObject({ shelfId: 's2', shelfPosition: 4 })
  })

  it('pickHidesToBuyEbooksAndThisShelf', async () => {
    const user = userEvent.setup()
    saved(windowCase)
    store.create(book('Paper'))
    store.create(book('To buy', { status: 'WISHLIST' }))
    store.create(book('Ebook'))
    store.create(book('Already here', { shelfId: 's1', shelfPosition: 1 }))
    vi.spyOn(store, 'ebookIds').mockReturnValue(new Set(['Ebook']))
    shelf()
    await user.click(screen.getByRole('button', { name: 'Pick from library' }))
    const dialog = screen.getByRole('dialog')
    expect(within(dialog).getByText('Paper')).toBeInTheDocument()
    for (const hidden of ['To buy', 'Ebook', 'Already here']) expect(within(dialog).queryByText(hidden)).not.toBeInTheDocument()
  })

  it('noScanButtonOnTheWebsite', () => {
    saved(windowCase)
    shelf()
    expect(screen.queryByRole('button', { name: /scan/i })).not.toBeInTheDocument()
  })

  it('withoutPlaceCanPlaceSeveral', async () => {
    const user = userEvent.setup()
    saved(windowCase)
    store.create(book('Alpha'))
    store.create(book('Beta'))
    render(
      <ToastContext.Provider value={toast}>
        <WithoutPlacePage store={store} onBack={() => {}} onOpenBook={() => {}} />
      </ToastContext.Provider>,
    )
    await user.click(screen.getByRole('checkbox', { name: 'Select Alpha' }))
    await user.click(screen.getByRole('checkbox', { name: 'Select Beta' }))
    await user.click(screen.getByRole('button', { name: 'Put 2 on a shelf' }))
    await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Living room › By the window › Shelf A2' }))
    expect(store.book('Alpha')?.shelfId).toBe('s2')
    expect(store.book('Beta')?.shelfId).toBe('s2')
    expect(screen.getByText('Every physical book has a place.')).toBeInTheDocument()
  })
})

describe('where a book is', () => {
  const toast = { show: vi.fn() }
  beforeEach(() => toast.show.mockClear())
  function Live({ id, onOpenShelf, onAddBookcase }: { id: string; onOpenShelf?: (s: string) => void; onAddBookcase?: () => void }) {
    const b = useSyncExternalStore(store.subscribe, () => store.book(id))
    return b ? <WhereItIs store={store} book={b} onOpenShelf={onOpenShelf} onAddBookcase={onAddBookcase} /> : null
  }
  const where = (id: string, handlers: { onOpenShelf?: (s: string) => void; onAddBookcase?: () => void } = {}) => render(
    <ToastContext.Provider value={toast}>
      <aside className="glance"><Live id={id} {...handlers} /></aside>
    </ToastContext.Provider>,
  )
  const A2 = 'Living room › By the window › Shelf A2'

  it('showsTheFullLocation', () => {
    saved(windowCase)
    for (const [i, t] of ['One', 'Two', 'Three', 'Target'].entries()) store.create(book(t, { shelfId: 's2', shelfPosition: i + 1 }))
    where('Target')
    expect(screen.getByRole('heading', { name: 'Where it is' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: A2 })).toBeInTheDocument()
    expect(screen.getByText('4th from the left')).toBeInTheDocument()
  })

  it('withoutAnOrderThereIsNoPositionLine', () => {
    saved(windowCase)
    store.create(book('Target', { shelfId: 's2' }))
    where('Target')
    expect(screen.queryByText(/from the left/)).not.toBeInTheDocument()
  })

  it('tappingTheLocationOpensTheShelf', async () => {
    const user = userEvent.setup()
    saved(windowCase)
    store.create(book('Target', { shelfId: 's2', shelfPosition: 1 }))
    const open = vi.fn()
    where('Target', { onOpenShelf: open })
    await user.click(screen.getByRole('button', { name: A2 }))
    expect(open).toHaveBeenCalledWith('s2')
  })

  it('withoutAPlaceOffersPuttingItOnAShelf', async () => {
    const user = userEvent.setup()
    saved(windowCase)
    store.create(book('Target'))
    where('Target')
    await user.click(screen.getByRole('button', { name: 'Put on a shelf' }))
    const dialog = screen.getByRole('dialog')
    expect(dialog.closest('.glance')).toBeNull()
    await user.click(within(dialog).getByRole('button', { name: A2 }))
    expect(store.book('Target')?.shelfId).toBe('s2')
    expect(screen.getByRole('button', { name: A2 })).toBeInTheDocument()
  })

  it('moveChangesTheShelf', async () => {
    const user = userEvent.setup()
    saved(windowCase)
    store.create(book('Target', { shelfId: 's1', shelfPosition: 7 }))
    where('Target')
    await user.click(screen.getByRole('button', { name: 'Move' }))
    const dialog = screen.getByRole('dialog')
    expect(within(dialog).queryByRole('button', { name: 'Living room › By the window › Shelf A1' })).not.toBeInTheDocument()
    await user.click(within(dialog).getByRole('button', { name: A2 }))
    expect(store.book('Target')?.shelfId).toBe('s2')
    const undo = toast.show.mock.calls[0][1] as { label: string; run: () => void }
    expect(undo.label).toBe('Undo')
    act(() => undo.run())
    expect(store.book('Target')).toMatchObject({ shelfId: 's1', shelfPosition: 7 })
  })

  it('takeOffTheShelfCanBeUndone', async () => {
    const user = userEvent.setup()
    saved(windowCase)
    store.create(book('Target', { shelfId: 's1', shelfPosition: 7 }))
    where('Target')
    await user.click(screen.getByRole('button', { name: 'Take off the shelf' }))
    expect(store.book('Target')?.shelfId).toBeNull()
    expect(screen.getByRole('button', { name: 'Put on a shelf' })).toBeInTheDocument()
    const undo = toast.show.mock.calls[0][1] as { run: () => void }
    act(() => undo.run())
    expect(store.book('Target')).toMatchObject({ shelfId: 's1', shelfPosition: 7 })
  })

  it('hiddenForToBuy', () => {
    saved(windowCase)
    store.create(book('Target', { status: 'WISHLIST', shelfId: 's1', shelfPosition: 1 }))
    where('Target')
    expect(screen.queryByText('Where it is')).not.toBeInTheDocument()
  })

  it('anEbookCanStillBePlaced', () => {
    saved(windowCase)
    store.create(book('Target'))
    vi.spyOn(store, 'ebookIds').mockReturnValue(new Set(['Target']))
    where('Target')
    expect(screen.getByRole('button', { name: 'Put on a shelf' })).toBeInTheDocument()
  })

  it('withoutBookcasesItLeadsToCreatingOne', async () => {
    const user = userEvent.setup()
    store.create(book('Target'))
    const add = vi.fn()
    where('Target', { onAddBookcase: add })
    expect(screen.queryByRole('button', { name: 'Put on a shelf' })).not.toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Add a bookcase' }))
    expect(add).toHaveBeenCalled()
  })

  it('aPlaceOnARemovedShelfOffersPuttingItBack', () => {
    saved(windowCase)
    store.create(book('Target', { shelfId: 'gone', shelfPosition: 1 }))
    where('Target')
    expect(screen.getByRole('button', { name: 'Put on a shelf' })).toBeInTheDocument()
  })

  it('lentShowsBoth', () => {
    saved(windowCase)
    store.create(book('Target', { shelfId: 's2', shelfPosition: 1, lentTo: 'Ana' }))
    render(<BookPage store={store} owner="me" id="Target" onBack={() => {}} />)
    expect(screen.getByText('Lent to Ana')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: A2 })).toBeInTheDocument()
  })

  it('aBookEditedKeepsItsLocation', async () => {
    const user = userEvent.setup()
    saved(windowCase)
    store.create(book('Target', { shelfId: 's2', shelfPosition: 5 }))
    render(<BookPage store={store} owner="me" id="Target" onBack={() => {}} />)
    const title = screen.getByLabelText('Title')
    await user.clear(title)
    await user.type(title, 'Renamed')
    await user.tab()
    expect(store.book('Target')).toMatchObject({ title: 'Renamed', shelfId: 's2', shelfPosition: 5 })
  })
})

describe('searching Without a place', () => {
  const toast = { show: vi.fn() }
  const page = () => render(
    <ToastContext.Provider value={toast}>
      <WithoutPlacePage store={store} onBack={() => {}} onOpenBook={() => {}} />
    </ToastContext.Provider>,
  )
  const search = () => screen.getByLabelText('Search your books')

  it('findsByTitleOrAuthorIgnoringAccentsAndCapitals', async () => {
    const user = userEvent.setup()
    saved(windowCase)
    store.create(book('Memorial do Convento', { authors: 'José Saramago' }))
    store.create(book('Stoner', { authors: 'John Williams' }))
    page()
    await user.type(search(), 'JOSE')
    expect(screen.getByText('Memorial do Convento')).toBeInTheDocument()
    expect(screen.queryByText('Stoner')).not.toBeInTheDocument()
    await user.clear(search())
    await user.type(search(), 'stoner')
    expect(screen.getByText('Stoner')).toBeInTheDocument()
    expect(screen.queryByText('Memorial do Convento')).not.toBeInTheDocument()
  })

  it('saysWhenNothingMatches', async () => {
    const user = userEvent.setup()
    saved(windowCase)
    store.create(book('Stoner'))
    page()
    await user.type(search(), 'nothing like this')
    expect(screen.getByText('No books match your search.')).toBeInTheDocument()
    expect(screen.queryByText('Every physical book has a place.')).not.toBeInTheDocument()
  })

  it('keepsWhatIsSelectedAndClearsAfterPlacing', async () => {
    const user = userEvent.setup()
    saved(windowCase)
    for (const t of ['Alpha', 'Beta', 'Gamma']) store.create(book(t))
    page()
    await user.click(screen.getByRole('checkbox', { name: 'Select Alpha' }))
    await user.type(search(), 'beta')
    expect(screen.queryByText('Alpha')).not.toBeInTheDocument()
    await user.click(screen.getByRole('checkbox', { name: 'Select Beta' }))
    await user.click(screen.getByRole('button', { name: 'Put 2 on a shelf' }))
    await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Living room › By the window › Shelf A2' }))
    expect(store.book('Alpha')?.shelfId).toBe('s2')
    expect(store.book('Beta')?.shelfId).toBe('s2')
    expect(search()).toHaveValue('')
    expect(screen.getByText('Gamma')).toBeInTheDocument()
  })

  it('noSearchWhenEveryBookHasAPlace', () => {
    saved(windowCase)
    page()
    expect(screen.queryByLabelText('Search your books')).not.toBeInTheDocument()
  })
})

describe('finding a book at home', () => {
  const label = 'Find a book by title or author'
  const page = (onOpenBook: (id: string) => void = () => {}) => render(
    <PhysicalLibraryPage store={store} onOpenBook={onOpenBook} onOpenBookcase={() => {}} onAdd={() => {}} onOpenUnplaced={() => {}} />,
  )

  it('findShowsWhereTheBookIs', async () => {
    const user = userEvent.setup()
    saved(windowCase)
    store.create(book('Memorial do Convento', { authors: 'José Saramago', shelfId: 's2', shelfPosition: 1 }))
    store.create(book('Ensaio sobre a Cegueira', { authors: 'José Saramago', shelfId: 's2', shelfPosition: 2 }))
    store.create(book('Stoner', { authors: 'John Williams', shelfId: 's1', shelfPosition: 1 }))
    page()
    await user.type(screen.getByLabelText(label), 'saramago cegueira')
    expect(screen.getByText('Ensaio sobre a Cegueira')).toBeInTheDocument()
    expect(screen.getByText('Living room › By the window › Shelf A2 · 2nd from the left')).toBeInTheDocument()
    expect(screen.queryByText('Memorial do Convento')).not.toBeInTheDocument()
    expect(screen.queryByText('By the window')).not.toBeInTheDocument()
  })

  it('findSaysWhenABookHasNoPlaceOrIsLent', async () => {
    const user = userEvent.setup()
    saved(windowCase)
    store.create(book('Waiting'))
    store.create(book('Away', { shelfId: 's1', shelfPosition: 1, lentTo: 'Ana', lentOn: 1 }))
    page()
    await user.type(screen.getByLabelText(label), 'someone')
    expect(screen.getByText('Without a place')).toBeInTheDocument()
    expect(screen.getByText('Living room › By the window › Shelf A1 · 1st from the left')).toBeInTheDocument()
    expect(screen.getByText('Lent to Ana')).toBeInTheDocument()
  })

  it('findOpensTheBook', async () => {
    const user = userEvent.setup()
    saved(windowCase)
    store.create(book('Stoner', { shelfId: 's1', shelfPosition: 1 }))
    const open = vi.fn()
    page(open)
    await user.type(screen.getByLabelText(label), 'stoner')
    await user.click(screen.getByRole('button', { name: /^Stoner/ }))
    expect(open).toHaveBeenCalledWith('Stoner')
  })

  it('clearingTheSearchBringsBackTheRooms', async () => {
    const user = userEvent.setup()
    saved(windowCase)
    store.create(book('Stoner', { shelfId: 's1', shelfPosition: 1 }))
    page()
    await user.type(screen.getByLabelText(label), 'stoner')
    expect(screen.queryByRole('heading', { name: 'Living room' })).not.toBeInTheDocument()
    await user.clear(screen.getByLabelText(label))
    expect(screen.getByRole('heading', { name: 'Living room' })).toBeInTheDocument()
  })

  it('findSaysWhenNothingMatches', async () => {
    const user = userEvent.setup()
    saved(windowCase)
    store.create(book('Stoner'))
    page()
    await user.type(screen.getByLabelText(label), 'nothing like this')
    expect(screen.getByText('No books match your search.')).toBeInTheDocument()
  })

  it('theSearchComesBackAfterOpeningABook', async () => {
    const user = userEvent.setup()
    saved(windowCase)
    store.create(book('Stoner', { shelfId: 's1', shelfPosition: 1 }))
    const { unmount } = page()
    await user.type(screen.getByLabelText(label), 'stoner')
    await user.click(screen.getByRole('button', { name: /^Stoner/ }))
    unmount()
    page()
    expect(screen.getByLabelText(label)).toHaveValue('stoner')
  })

  it('anOrdinaryVisitStartsWithAnEmptySearch', async () => {
    const user = userEvent.setup()
    saved(windowCase)
    store.create(book('Stoner', { shelfId: 's1', shelfPosition: 1 }))
    const { unmount } = page()
    await user.type(screen.getByLabelText(label), 'stoner')
    unmount()
    page()
    expect(screen.getByLabelText(label)).toHaveValue('')
  })
})
