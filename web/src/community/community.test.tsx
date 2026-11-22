import { act, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { IDBFactory } from 'fake-indexeddb'
import type { ReactNode } from 'react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { Publisher } from '../share/publisher'
import { SharingContext } from '../share/SharingContext'
import { rowKey, type SyncApi } from '../sync/api'
import type { Book } from '../sync/model'
import { StoreContext } from '../sync/StoreContext'
import { LibraryStore } from '../sync/store'
import { ToastContext } from '../ui/toastContext'
import { AccountPage } from './AccountPage'
import { CommunityError, communityMessage, createCommunityApi, relationshipFrom, type CommunityApi } from './api'
import { BookCommunity, ShareSwitch } from './BookCommunity'
import { CommunityProvider } from './CommunityProvider'
import type { CommunityNav } from './useCommunity'
import { CommunityPage } from './CommunityPage'
import type { BookSuggestion, FeedEntry, Person, Profile } from './model'
import { PeoplePage } from './PeoplePage'
import { suggestionSentText, suggestionText } from './text'
import { PersonPage } from './PersonPage'
import { SuggestionInbox } from './inbox'
import { FollowSuggestions, SuggestionBadge } from './SuggestionBadge'

const h = vi.hoisted(() => ({ signOut: vi.fn(async () => {}) }))
vi.mock('../auth/signOut', () => ({ signOut: h.signOut }))

const syncApi: SyncApi = {
  pull: async () => ({ rows: [], last: 0 }),
  push: async (changes) => ({ applied: changes.map((c) => rowKey(c.table, c.id)), rejected: [] }),
  onPing: () => () => {},
}

const person = (id: string, name: string, over: Partial<Person> = {}): Person => ({
  id, username: name.toLowerCase(), displayName: name, avatarUrl: null, visibility: 'friends', ...over,
})
const ana = person('ana', 'Ana')
const rui = person('rui', 'Rui')
const me: Profile = { ...person('me', 'Mario'), shareActivity: true }

let nextId = 1
function entry(over: Partial<FeedEntry> = {}): FeedEntry {
  const id = nextId++
  const at = Date.now() - 3_600_000
  return {
    id, actor: ana, kind: 'finished', rating: null, progress: null, createdAt: at, createdAtText: new Date(at).toISOString(),
    book: { shareId: `s${id}`, isbn: '9780441172719', title: 'Dune', authors: 'Frank Herbert', coverUrl: null, blurb: 'Spice.', pages: 412, currentPage: null },
    review: null, likeCount: 0, likedByMe: false, ...over,
  }
}

function book(id: string, over: Partial<Book> = {}): Book {
  return {
    id, isbn: null, title: id, authors: 'Someone', publisher: null, publishedYear: null, pageCount: null, coverUrl: null,
    description: null, status: 'TO_READ', rating: null, notes: '', dateAdded: 0, dateRead: null, dateStarted: null,
    currentPage: null, progressPercent: null, review: '', tags: [], series: null, seriesNumber: null, lentTo: null,
    lentOn: null, deleted: false, ...over,
  }
}

function fakeApi(over: Partial<CommunityApi> = {}): CommunityApi {
  return {
    me: 'me',
    myProfile: vi.fn(async () => me),
    isUsernameAvailable: vi.fn(async () => true),
    createProfile: vi.fn(async (username: string, displayName: string) => ({ ...me, username, displayName })),
    updateProfile: vi.fn(async (displayName: string, visibility: Profile['visibility']) => ({ ...me, displayName, visibility })),
    uploadAvatar: vi.fn(async () => me),
    setShareActivity: vi.fn(async (enabled: boolean) => ({ ...me, shareActivity: enabled })),
    deleteAccount: vi.fn(async () => {}),
    person: vi.fn(async (id: string) => (id === 'ana' ? ana : null)),
    friends: vi.fn(async () => []),
    incomingRequests: vi.fn(async () => []),
    outgoingRequests: vi.fn(async () => []),
    search: vi.fn(async () => []),
    relationship: vi.fn(async () => 'none' as const),
    sendRequest: vi.fn(async () => 'sent' as const),
    respond: vi.fn(async () => {}),
    cancelRequest: vi.fn(async () => {}),
    removeFriend: vi.fn(async () => {}),
    suggestedReaders: vi.fn(async () => []),
    block: vi.fn(async () => {}),
    unblock: vi.fn(async () => {}),
    blockedPeople: vi.fn(async () => []),
    report: vi.fn(async () => {}),
    feed: vi.fn(async () => ({ entries: [], next: null })),
    personActivity: vi.fn(async () => ({ entries: [], next: null })),
    like: vi.fn(async () => {}),
    unlike: vi.fn(async () => {}),
    likers: vi.fn(async () => []),
    friendsReading: vi.fn(async () => []),
    friendsWithBook: vi.fn(async () => []),
    bookScore: vi.fn(async () => ({ ratings: 0, average: null, perScore: [0, 0, 0, 0, 0], reviews: 0 })),
    bookReviews: vi.fn(async () => []),
    suggestBook: vi.fn(async (_book: unknown, recipients: string[]) => recipients.length),
    bookSuggestions: vi.fn(async () => []),
    markSuggestionsSeen: vi.fn(async () => {}),
    closeSuggestion: vi.fn(async () => {}),
    ...over,
  } as CommunityApi
}

const nav: CommunityNav = {
  personHref: (id) => `/person/${id}`,
  openPerson: vi.fn(),
  openBook: vi.fn(),
  openPeople: vi.fn(),
  openAccount: vi.fn(),
  openFeed: vi.fn(),
}
const toast = { show: vi.fn() }
/** A drawn cover carries the title too. */
const TITLE = '.feed-book__title'

let store: LibraryStore

beforeEach(async () => {
  vi.clearAllMocks()
  globalThis.indexedDB = new IDBFactory()
  window.localStorage.clear()
  store = new LibraryStore({ api: syncApi, owner: 'me', pollMs: 0, retryDelays: [60_000] })
  await store.load()
})
afterEach(async () => {
  await store.close()
})

function show(api: CommunityApi, ui: ReactNode, publisher: Publisher | null = null) {
  return render(
    <StoreContext.Provider value={store}>
      <SharingContext.Provider value={publisher}>
        <ToastContext.Provider value={toast}>
          <CommunityProvider api={api} nav={nav}>{ui}</CommunityProvider>
        </ToastContext.Provider>
      </SharingContext.Provider>
    </StoreContext.Provider>,
  )
}

describe('joining', () => {
  const noProfile = () => fakeApi({ myProfile: vi.fn(async () => null) })

  it('withoutAProfileItAsksForOneAndSaysWhatIsShared', async () => {
    show(noProfile(), <CommunityPage />)
    expect(await screen.findByRole('heading', { name: 'Join the community' })).toBeInTheDocument()
    expect(screen.getByText(/changes to your books are shared/)).toBeInTheDocument()
    expect(screen.getByText(/keep any book private/)).toBeInTheDocument()
  })

  it('createsTheProfileAndOpensTheFeed', async () => {
    const user = userEvent.setup()
    const api = noProfile()
    show(api, <CommunityPage />)
    await user.type(await screen.findByLabelText('Username'), ' Mario.C ')
    await user.type(screen.getByLabelText('Name'), 'Mario')
    await user.click(screen.getByRole('button', { name: 'Create profile' }))
    expect(api.createProfile).toHaveBeenCalledWith('mario.c', 'Mario')
    // A new member has no phone-only exclusions: the site can post their books from now on.
    await waitFor(() => expect(store.pref('share_exclusions.synced')).toBe('1'))
    expect(await screen.findByRole('heading', { name: 'Community' })).toBeInTheDocument()
  })

  it('aBadUsernameIsExplainedWithoutACall', async () => {
    const user = userEvent.setup()
    const api = noProfile()
    show(api, <CommunityPage />)
    await user.type(await screen.findByLabelText('Username'), 'a!')
    await user.type(screen.getByLabelText('Name'), 'Mario')
    await user.click(screen.getByRole('button', { name: 'Create profile' }))
    expect(screen.getByRole('alert')).toHaveTextContent('3–20 characters')
    expect(api.createProfile).not.toHaveBeenCalled()
  })

  it('aTakenUsernameIsExplained', async () => {
    const user = userEvent.setup()
    const api = fakeApi({ myProfile: vi.fn(async () => null), isUsernameAvailable: vi.fn(async () => false) })
    show(api, <CommunityPage />)
    await user.type(await screen.findByLabelText('Username'), 'taken')
    await user.type(screen.getByLabelText('Name'), 'Mario')
    await user.click(screen.getByRole('button', { name: 'Create profile' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('That username is taken.')
    expect(api.createProfile).not.toHaveBeenCalled()
  })

  it('whenTheServerCannotBeReachedTheLibraryIsNotBlamed', async () => {
    const user = userEvent.setup()
    const myProfile = vi.fn<() => Promise<Profile | null>>().mockRejectedValueOnce(new CommunityError('offline', 'x')).mockResolvedValue(me)
    show(fakeApi({ myProfile }), <CommunityPage />)
    expect(await screen.findByRole('alert')).toHaveTextContent('Your library still works')
    await user.click(screen.getByRole('button', { name: 'Try again' }))
    expect(await screen.findByRole('heading', { name: 'Community' })).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'Join the community' })).not.toBeInTheDocument()
  })
})

describe('coming back online', () => {
  it('theProfileIsLoadedAgainSoTheBookSwitchReturns', async () => {
    const myProfile = vi.fn<() => Promise<Profile | null>>().mockRejectedValueOnce(new CommunityError('offline', 'x')).mockResolvedValue(me)
    store.create(book('b1'))
    const client = { rpc: vi.fn(async () => ({ data: [], error: null })) }
    const publisher = new Publisher({ store, client, debounceMs: 0, owner: 'me', currentOwner: () => 'me' })
    await publisher.start()
    show(fakeApi({ myProfile }), <ShareSwitch id="b1" />, publisher)
    await waitFor(() => expect(myProfile).toHaveBeenCalledTimes(1))
    expect(screen.queryByRole('switch')).not.toBeInTheDocument()
    act(() => { window.dispatchEvent(new Event('online')) })
    expect(await screen.findByRole('switch')).toBeInTheDocument()
    publisher.stop()
  })
})

describe('the feed', () => {
  it('showsAnUpdateWithItsBook', async () => {
    const api = fakeApi({ feed: vi.fn(async () => ({ entries: [entry({ rating: 4, kind: 'rated' })], next: null })) })
    show(api, <CommunityPage />)
    expect(await screen.findByText('Dune', { selector: TITLE })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Ana' })).toHaveAttribute('href', '/person/ana')
    expect(screen.getByRole('img', { name: 'Rated 4 out of 5' })).toHaveTextContent('★★★★☆')
    expect(screen.getByText(/Frank Herbert · 412 pages/)).toBeInTheDocument()
  })

  it('switchingToEveryoneLoadsItAndIsRemembered', async () => {
    const user = userEvent.setup()
    const api = fakeApi()
    show(api, <CommunityPage />)
    await user.click(await screen.findByRole('button', { name: 'Everyone' }))
    await waitFor(() => expect(api.feed).toHaveBeenCalledWith('everyone', null))
    expect(window.localStorage.getItem('mybooks.community.scope')).toBe('everyone')
  })

  it('showMoreContinuesFromTheLastEntryAndSkipsRepeats', async () => {
    const user = userEvent.setup()
    const first = entry()
    const second = entry({ book: { ...entry().book, title: 'Emma' } })
    const cursor = { createdAt: first.createdAtText, id: first.id }
    const feed = vi.fn(async (_s: string, c: unknown) =>
      c ? { entries: [first, second], next: null } : { entries: [first], next: cursor })
    show(fakeApi({ feed: feed as unknown as CommunityApi['feed'] }), <CommunityPage />)
    await user.click(await screen.findByRole('button', { name: 'Show more' }))
    expect(await screen.findByText('Emma', { selector: TITLE })).toBeInTheDocument()
    expect(feed).toHaveBeenLastCalledWith('friends', cursor)
    expect(screen.getAllByText('Dune', { selector: TITLE })).toHaveLength(1)
    expect(screen.queryByRole('button', { name: 'Show more' })).not.toBeInTheDocument()
  })

  it('aLikeShowsAtOnce', async () => {
    const user = userEvent.setup()
    const e = entry()
    const api = fakeApi({ feed: vi.fn(async () => ({ entries: [e], next: null })) })
    show(api, <CommunityPage />)
    await user.click(await screen.findByRole('button', { name: /^Like Ana/ }))
    expect(screen.getByRole('button', { name: /^Like Ana/ })).toHaveAttribute('aria-pressed', 'true')
    expect(screen.getByRole('button', { name: '1 like' })).toBeInTheDocument()
    expect(api.like).toHaveBeenCalledWith(e.id)
  })

  it('aLikeThatFailsIsTakenBackAndExplained', async () => {
    const user = userEvent.setup()
    const api = fakeApi({
      feed: vi.fn(async () => ({ entries: [entry()], next: null })),
      like: vi.fn(async () => { throw new CommunityError('offline', 'x') }),
    })
    show(api, <CommunityPage />)
    await user.click(await screen.findByRole('button', { name: /^Like Ana/ }))
    await waitFor(() => expect(screen.getByRole('button', { name: /^Like Ana/ })).toHaveAttribute('aria-pressed', 'false'))
    expect(toast.show).toHaveBeenCalledWith(expect.stringContaining('offline'))
  })

  it('wantToReadAddsTheBookAndThenSaysItIsThere', async () => {
    const user = userEvent.setup()
    show(fakeApi({ feed: vi.fn(async () => ({ entries: [entry()], next: null })) }), <CommunityPage />)
    await user.click(await screen.findByRole('button', { name: 'Want to read' }))
    const added = store.books()
    expect(added).toHaveLength(1)
    expect(added[0]).toMatchObject({ title: 'Dune', status: 'TO_READ', isbn: '9780441172719' })
    await user.click(await screen.findByRole('button', { name: /In your library · To read/ }))
    expect(nav.openBook).toHaveBeenCalledWith(added[0].id)
  })

  it('toBuyAddsItToTheWishlist', async () => {
    const user = userEvent.setup()
    show(fakeApi({ feed: vi.fn(async () => ({ entries: [entry()], next: null })) }), <CommunityPage />)
    await user.click(await screen.findByRole('button', { name: 'To buy' }))
    expect(store.books()[0].status).toBe('WISHLIST')
  })

  it('aBookAlreadyInTheLibraryIsRecognisedByTitleAndAuthor', async () => {
    store.create(book('mine', { title: 'Dune: Deluxe', authors: 'Frank Herbert', status: 'READ' }))
    show(fakeApi({ feed: vi.fn(async () => ({ entries: [entry()], next: null })) }), <CommunityPage />)
    expect(await screen.findByRole('button', { name: /In your library · Read/ })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Want to read' })).not.toBeInTheDocument()
  })

  it('reportingSendsTheReasonAndTheEntry', async () => {
    const user = userEvent.setup()
    const e = entry()
    const api = fakeApi({ feed: vi.fn(async () => ({ entries: [e], next: null })) })
    show(api, <CommunityPage />)
    await user.click(await screen.findByRole('button', { name: 'Report' }))
    const dialog = screen.getByRole('dialog', { name: 'Report Ana' })
    await user.click(within(dialog).getByLabelText('Harassment'))
    await user.type(within(dialog).getByLabelText(/Anything to add/), 'rude')
    await user.click(within(dialog).getByRole('button', { name: 'Send report' }))
    expect(api.report).toHaveBeenCalledWith('ana', 'harassment', 'rude', e.id)
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
  })

  it('myOwnUpdatesHaveNoHeart', async () => {
    show(fakeApi({ feed: vi.fn(async () => ({ entries: [entry({ actor: person('me', 'Mario') })], next: null })) }), <CommunityPage />)
    await screen.findByText('Dune', { selector: TITLE })
    expect(screen.queryByRole('button', { name: /^Like/ })).not.toBeInTheDocument()
  })

  it('myOwnUpdatesHaveNoReportButton', async () => {
    show(fakeApi({ feed: vi.fn(async () => ({ entries: [entry({ actor: person('me', 'Mario') })], next: null })) }), <CommunityPage />)
    await screen.findByText('Dune', { selector: TITLE })
    expect(screen.queryByRole('button', { name: 'Report' })).not.toBeInTheDocument()
  })

  it('friendsReadingShowsOnlyOnTheFriendsTab', async () => {
    const user = userEvent.setup()
    const api = fakeApi({
      friendsReading: vi.fn(async () => [{ person: ana, book: entry().book, progress: 53, updatedAt: Date.now() }]),
    })
    show(api, <CommunityPage />)
    expect(await screen.findByRole('heading', { name: 'Friends are reading' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Ana is reading Dune' })).toHaveTextContent('Ana· 53%')
    await user.click(screen.getByRole('button', { name: 'Everyone' }))
    expect(screen.queryByRole('heading', { name: 'Friends are reading' })).not.toBeInTheDocument()
  })

  it('reviewsAndNamesAreShownAsPlainText', async () => {
    const nasty = '<img src=x onerror=alert(1)>'
    show(fakeApi({
      feed: vi.fn(async () => ({ entries: [entry({ kind: 'reviewed', review: nasty, actor: person('x', nasty) })], next: null })),
    }), <CommunityPage />)
    expect(await screen.findByText(nasty, { selector: 'blockquote p' })).toBeInTheDocument()
    expect(document.querySelector('img[src="x"]')).toBeNull()
  })
})

describe('people', () => {
  it('acceptingARequestAsksTheServerAndRefreshes', async () => {
    const user = userEvent.setup()
    const incomingRequests = vi.fn<() => Promise<Person[]>>().mockResolvedValueOnce([ana]).mockResolvedValue([])
    const friends = vi.fn<() => Promise<Person[]>>().mockResolvedValueOnce([]).mockResolvedValue([ana])
    const api = fakeApi({ incomingRequests, friends })
    show(api, <PeoplePage />)
    await user.click(await screen.findByRole('button', { name: 'Accept Ana' }))
    expect(api.respond).toHaveBeenCalledWith('ana', true)
    expect(await screen.findByRole('button', { name: 'Remove Ana' })).toBeInTheDocument()
  })

  it('searchFindsPeopleAndAddsThem', async () => {
    const user = userEvent.setup()
    const api = fakeApi({ search: vi.fn(async () => [{ person: rui, relationship: 'none' as const }]) })
    show(api, <PeoplePage />)
    await user.type(await screen.findByRole('searchbox'), 'ru')
    await user.click(await screen.findByRole('button', { name: 'Add Rui' }, { timeout: 2000 }))
    expect(api.sendRequest).toHaveBeenCalledWith('rui')
    expect(await screen.findByRole('button', { name: 'Cancel request to Rui' })).toBeInTheDocument()
  })

  it('removingAFriendAsksFirst', async () => {
    const user = userEvent.setup()
    const api = fakeApi({ friends: vi.fn(async () => [ana]) })
    show(api, <PeoplePage />)
    await user.click(await screen.findByRole('button', { name: 'Remove Ana' }))
    expect(api.removeFriend).not.toHaveBeenCalled()
    await user.click(within(screen.getByRole('dialog', { name: 'Remove Ana?' })).getByRole('button', { name: 'Remove' }))
    expect(api.removeFriend).toHaveBeenCalledWith('ana')
  })

  it('suggestedReadersLeaveOutPeopleAlreadyKnown', async () => {
    const reader = (p: Person) => ({ person: p, sharedBooks: 2, mutualFriends: 0, mutualNames: [], topTitle: 'Dune' })
    const api = fakeApi({ friends: vi.fn(async () => [ana]), suggestedReaders: vi.fn(async () => [reader(ana), reader(rui)]) })
    show(api, <PeoplePage />)
    expect(await screen.findByRole('button', { name: 'Add Rui' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Add Ana' })).not.toBeInTheDocument()
    expect(screen.getByText('Also loved Dune and 1 more')).toBeInTheDocument()
  })

  it('suggestionTextNamesMutualFriends', () => {
    expect(suggestionText({ person: rui, sharedBooks: 0, mutualFriends: 3, mutualNames: ['Ana', 'Zé'], topTitle: null }))
      .toBe('Friends with Ana and Zé and 1 more')
  })
})

describe('a person', () => {
  it('blockingAsksFirstThenLeaves', async () => {
    const user = userEvent.setup()
    const api = fakeApi()
    show(api, <PersonPage id="ana" />)
    await user.click(await screen.findByRole('button', { name: 'Block' }))
    expect(api.block).not.toHaveBeenCalled()
    await user.click(within(screen.getByRole('dialog', { name: 'Block Ana?' })).getByRole('button', { name: 'Block' }))
    expect(api.block).toHaveBeenCalledWith('ana')
    await waitFor(() => expect(nav.openFeed).toHaveBeenCalled())
  })

  it('someoneHiddenByABlockIsNotAvailable', async () => {
    show(fakeApi(), <PersonPage id="gone" />)
    expect(await screen.findByRole('heading', { name: 'Not available' })).toBeInTheDocument()
  })

  it('saysWhyAFriendsOnlyProfileShowsNothing', async () => {
    show(fakeApi(), <PersonPage id="ana" />)
    expect(await screen.findByText('Only friends can see what they are reading.')).toBeInTheDocument()
  })
})

describe('your profile', () => {
  it('stoppingSharingAsksFirst', async () => {
    const user = userEvent.setup()
    const api = fakeApi()
    show(api, <AccountPage />)
    await user.click(await screen.findByRole('switch', { name: 'Share my reading activity' }))
    expect(api.setShareActivity).not.toHaveBeenCalled()
    await user.click(within(screen.getByRole('dialog', { name: 'Stop sharing?' })).getByRole('button', { name: 'Stop sharing' }))
    expect(api.setShareActivity).toHaveBeenCalledWith(false)
    await waitFor(() => expect(screen.getByRole('switch', { name: 'Share my reading activity' })).not.toBeChecked())
  })

  it('visibilityIsSaved', async () => {
    const user = userEvent.setup()
    const api = fakeApi()
    show(api, <AccountPage />)
    await user.click(await screen.findByRole('radio', { name: 'Everyone' }))
    expect(api.updateProfile).toHaveBeenCalledWith('Mario', 'public')
  })

  it('deletingNeedsTheUsernameTyped', async () => {
    const user = userEvent.setup()
    const api = fakeApi()
    show(api, <AccountPage />)
    await user.click(await screen.findByRole('button', { name: 'Delete account' }))
    const dialog = screen.getByRole('dialog', { name: 'Delete your account?' })
    expect(within(dialog).getByText(/library saved in the cloud/)).toHaveTextContent('on your phones and in this browser')
    const confirm = within(dialog).getByRole('button', { name: 'Delete account' })
    expect(confirm).toBeDisabled()
    await user.type(within(dialog).getByLabelText(/Type your username/), 'wrong')
    expect(confirm).toBeDisabled()
    await user.clear(within(dialog).getByLabelText(/Type your username/))
    await user.type(within(dialog).getByLabelText(/Type your username/), 'mario')
    await user.click(confirm)
    expect(api.deleteAccount).toHaveBeenCalled()
    await waitFor(() => expect(h.signOut).toHaveBeenCalled())
  })

  it('aFailedDeleteDoesNotSignOut', async () => {
    const user = userEvent.setup()
    const api = fakeApi({ deleteAccount: vi.fn(async () => { throw new CommunityError('offline', 'x') }) })
    show(api, <AccountPage />)
    await user.click(await screen.findByRole('button', { name: 'Delete account' }))
    const dialog = screen.getByRole('dialog')
    await user.type(within(dialog).getByLabelText(/Type your username/), 'mario')
    await user.click(within(dialog).getByRole('button', { name: 'Delete account' }))
    expect(await within(dialog).findByRole('alert')).toHaveTextContent('offline')
    expect(h.signOut).not.toHaveBeenCalled()
  })

  it('unblockingRemovesThemFromTheList', async () => {
    const user = userEvent.setup()
    const api = fakeApi({ blockedPeople: vi.fn(async () => [rui]) })
    show(api, <AccountPage />)
    await user.click(await screen.findByRole('button', { name: 'Unblock Rui' }))
    expect(api.unblock).toHaveBeenCalledWith('rui')
    await waitFor(() => expect(screen.queryByRole('button', { name: 'Unblock Rui' })).not.toBeInTheDocument())
  })
})

describe('a book', () => {
  const rpc = { rpc: vi.fn(async (fn: string) => ({ data: fn === 'my_shared_book_ids' ? ['b1'] : null, error: null })) }
  const publisherFor = async () => {
    const publisher = new Publisher({ store, client: rpc, debounceMs: 0, owner: 'me', currentOwner: () => 'me' })
    await publisher.start()
    return publisher
  }

  it('theSwitchKeepsABookPrivateOnEveryDevice', async () => {
    const user = userEvent.setup()
    store.create(book('b1'))
    const publisher = await publisherFor()
    show(fakeApi(), <ShareSwitch id="b1" />, publisher)
    const toggle = await screen.findByRole('switch', { name: "Don't share this book" })
    expect(toggle).not.toBeChecked()
    await user.click(toggle)
    expect(store.pref('share_excluded.b1')).toBe('1')
    expect(screen.getByRole('switch')).toBeChecked()
    await publisher.idle()
    expect(rpc.rpc).toHaveBeenCalledWith('unpublish_book', { id: 'b1' })
    publisher.stop()
  })

  it('theSwitchIsHiddenWhileTheAccountDoesNotShare', async () => {
    store.create(book('b1'))
    const publisher = await publisherFor()
    const api = fakeApi({ myProfile: vi.fn(async () => ({ ...me, shareActivity: false })) })
    show(api, <ShareSwitch id="b1" />, publisher)
    await waitFor(() => expect(api.myProfile).toHaveBeenCalled())
    await new Promise((r) => setTimeout(r, 10))
    expect(screen.queryByRole('switch')).not.toBeInTheDocument()
    publisher.stop()
  })

  it('friendsAndReadersShowOnABookWithAnIsbn', async () => {
    const api = fakeApi({
      friendsWithBook: vi.fn(async () => [{ person: ana, status: 'READING' as const, rating: null, progress: 40, review: '' }]),
      bookScore: vi.fn(async () => ({ ratings: 2, average: 4.5, perScore: [1, 1, 0, 0, 0], reviews: 1 })),
      bookReviews: vi.fn(async () => [{ person: rui, rating: 5, review: 'Loved it', updatedAt: Date.now() }]),
    })
    show(api, <BookCommunity book={book('b1', { isbn: '123' })} />)
    expect(await screen.findByText('is reading it · 40%')).toBeInTheDocument()
    expect(await screen.findByText('Loved it')).toBeInTheDocument()
    expect(screen.getByText('4.5')).toBeInTheDocument()
    expect(api.friendsWithBook).toHaveBeenCalledWith('123')
  })

  it('aBookWithoutAnIsbnAsksNothing', async () => {
    const api = fakeApi()
    show(api, <BookCommunity book={book('b1')} />)
    await waitFor(() => expect(api.myProfile).toHaveBeenCalled())
    await new Promise((r) => setTimeout(r, 10))
    expect(api.friendsWithBook).not.toHaveBeenCalled()
    expect(api.bookScore).not.toHaveBeenCalled()
  })

  it('aFailureIsQuietOnABookPage', async () => {
    const api = fakeApi({
      friendsWithBook: vi.fn(async () => { throw new CommunityError('offline', 'x') }),
      bookScore: vi.fn(async () => { throw new CommunityError('offline', 'x') }),
    })
    show(api, <BookCommunity book={book('b1', { isbn: '123' })} />)
    await waitFor(() => expect(api.friendsWithBook).toHaveBeenCalled())
    await new Promise((r) => setTimeout(r, 10))
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })
})

describe('the server calls', () => {
  it('relationships', () => {
    const row = (requester: string, addressee: string, status: string) => ({ requester, addressee, status })
    expect(relationshipFrom([], 'me', 'ana')).toBe('none')
    expect(relationshipFrom([row('me', 'ana', 'accepted')], 'me', 'ana')).toBe('friends')
    expect(relationshipFrom([row('ana', 'me', 'accepted')], 'me', 'ana')).toBe('friends')
    expect(relationshipFrom([row('me', 'ana', 'pending')], 'me', 'ana')).toBe('sent')
    expect(relationshipFrom([row('ana', 'me', 'pending')], 'me', 'ana')).toBe('received')
    expect(relationshipFrom([row('me', 'rui', 'accepted')], 'me', 'ana')).toBe('none')
  })

  const feedRow = (id: number) => ({
    id, kind: 'progress', rating: null, progress: 53, created_at: '2026-11-21T10:00:00.123456+00:00', actor_id: 'ana',
    username: 'ana', display_name: 'Ana', avatar_path: 'ana/a.jpg', book_id: 'b', isbn: null, title: 'Dune', authors: 'F',
    cover_url: null, review: null, like_count: 2, liked_by_me: true, book_blurb: 'x', book_pages: 300, book_current_page: 150,
  })
  const client = (data: unknown, error: unknown = null) => ({
    rpc: vi.fn(async () => ({ data, error })),
    storage: { from: () => ({ getPublicUrl: (p: string) => ({ data: { publicUrl: `https://cdn/${p}` } }) }) },
  }) as unknown as Parameters<typeof createCommunityApi>[0]

  it('feedRowsBecomeEntries', async () => {
    const page = await createCommunityApi(client([feedRow(1)]), 'me').feed('friends')
    expect(page.entries[0]).toMatchObject({
      id: 1, kind: 'progress', progress: 53, likeCount: 2, likedByMe: true,
      actor: { id: 'ana', displayName: 'Ana', avatarUrl: 'https://cdn/ana/a.jpg' },
      book: { pages: 300, currentPage: 150, blurb: 'x' },
    })
    expect(page.next).toBeNull()
  })

  it('aFullPageContinuesFromTheServersExactTime', async () => {
    const rows = Array.from({ length: 20 }, (_, i) => feedRow(i + 1))
    const page = await createCommunityApi(client(rows), 'me').feed('friends')
    // The microseconds matter: rounding them to ms would repeat or skip entries at the page boundary.
    expect(page.next).toEqual({ createdAt: '2026-11-21T10:00:00.123456+00:00', id: 20 })
  })

  it.each([
    ['23505', 'username_taken'], ['P0002', 'not_available'], ['22023', 'rejected'], ['XX000', 'other'],
  ])('error %s', async (code, kind) => {
    await expect(createCommunityApi(client(null, { message: 'x', code }), 'me').feed('friends')).rejects.toMatchObject({ kind })
  })

  it('noConnectionIsOffline', async () => {
    await expect(createCommunityApi(client(null, { message: 'TypeError: Failed to fetch' }), 'me').feed('friends'))
      .rejects.toMatchObject({ kind: 'offline' })
  })

  it('aShortSearchMakesNoCall', async () => {
    const c = client([])
    expect(await createCommunityApi(c, 'me').search(' a ')).toEqual([])
    expect(c.rpc).not.toHaveBeenCalled()
  })
})

describe('book suggestions', () => {
  const suggestion = (id: number, over: Partial<BookSuggestion> = {}): BookSuggestion => ({
    id, sender: rui, note: '', createdAt: Date.now() - 60_000, seen: false,
    book: { shareId: `suggestion:${id}`, isbn: '9780141439587', title: 'Stoner', authors: 'John Williams', coverUrl: null, blurb: null, pages: 288, currentPage: null },
    ...over,
  })
  const withSuggestions = (...items: BookSuggestion[]) => fakeApi({ bookSuggestions: vi.fn(async () => items) })

  it('showsWhoSuggestedItWithTheNoteAndMarksThemSeen', async () => {
    const api = withSuggestions(suggestion(1, { note: "You'd love this" }))
    show(api, <CommunityPage />)
    expect(await screen.findByRole('heading', { name: 'Suggested to you' })).toBeInTheDocument()
    expect(screen.getByText("“You'd love this”")).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Rui' })).toBeInTheDocument()
    await waitFor(() => expect(api.markSuggestionsSeen).toHaveBeenCalledWith([1]))
  })

  it('isShownOnTheEveryoneTabToo', async () => {
    window.localStorage.setItem('mybooks.community.scope', 'everyone')
    const api = withSuggestions(suggestion(1))
    show(api, <CommunityPage />)
    expect(await screen.findByRole('heading', { name: 'Suggested to you' })).toBeInTheDocument()
    await waitFor(() => expect(api.markSuggestionsSeen).toHaveBeenCalledWith([1]))
  })

  it('wantToReadAddsTheBookAndClosesTheSuggestion', async () => {
    const user = userEvent.setup()
    const api = withSuggestions(suggestion(1))
    show(api, <CommunityPage />)
    await user.click(await screen.findByRole('button', { name: 'Want to read Stoner' }))
    await waitFor(() => expect(api.closeSuggestion).toHaveBeenCalledWith(1))
    const added = store.books().find((b) => b.title === 'Stoner')
    expect(added?.status).toBe('TO_READ')
    expect(toast.show).toHaveBeenCalledWith('Added to To read', expect.anything())
    await waitFor(() => expect(screen.queryByRole('heading', { name: 'Suggested to you' })).not.toBeInTheDocument())
  })

  it('aBookAlreadyInTheLibraryIsNotAddedTwice', async () => {
    const user = userEvent.setup()
    const { addBook } = await import('../add/addBook')
    addBook(store, { isbn: '9780141439587', title: 'Stoner', authors: ['John Williams'], publisher: null, year: null, pages: null, coverUrl: null, description: null }, Date.now(), 'READ')
    const api = withSuggestions(suggestion(1))
    show(api, <CommunityPage />)
    await user.click(await screen.findByRole('button', { name: 'To buy Stoner' }))
    await waitFor(() => expect(api.closeSuggestion).toHaveBeenCalledWith(1))
    expect(store.books().filter((b) => b.title === 'Stoner')).toHaveLength(1)
    expect(store.books().find((b) => b.title === 'Stoner')?.status).toBe('READ')
    expect(toast.show).toHaveBeenCalledWith('Already in your library', expect.anything())
  })

  it('aMalformedIsbnIsNotStored', async () => {
    const user = userEvent.setup()
    const bad = suggestion(1)
    const api = withSuggestions({ ...bad, book: { ...bad.book, isbn: '9781' } })
    show(api, <CommunityPage />)
    await user.click(await screen.findByRole('button', { name: 'Want to read Stoner' }))
    await waitFor(() => expect(api.closeSuggestion).toHaveBeenCalledWith(1))
    expect(store.books().find((b) => b.title === 'Stoner')?.isbn).toBeNull()
  })

  it('aFailedDismissShowsItAgain', async () => {
    const user = userEvent.setup()
    const api = fakeApi({
      bookSuggestions: vi.fn(async () => [suggestion(1)]),
      closeSuggestion: vi.fn(async () => { throw new CommunityError('offline', 'offline') }),
    })
    show(api, <CommunityPage />)
    await user.click(await screen.findByRole('button', { name: 'Dismiss Stoner' }))
    await waitFor(() => expect(toast.show).toHaveBeenCalledWith("Couldn't dismiss the suggestion"))
    expect(screen.getByRole('heading', { name: 'Suggested to you' })).toBeInTheDocument()
  })

  it('aReportCarriesTheNote', async () => {
    const user = userEvent.setup()
    const api = withSuggestions(suggestion(1, { note: 'buy my thing' }))
    show(api, <CommunityPage />)
    await user.click(await screen.findByRole('button', { name: 'Report suggestion of Stoner' }))
    await user.click(screen.getByRole('button', { name: 'Send report' }))
    await waitFor(() => expect(api.report).toHaveBeenCalledWith('rui', 'spam', 'Note on the suggestion: buy my thing', null))
  })

  it('sendsTheBookToTheChosenFriendsWithoutAPrivateCover', async () => {
    const user = userEvent.setup()
    const api = fakeApi({ friends: vi.fn(async () => [rui, ana]) })
    show(api, <BookCommunity book={book('Stoner', { coverUrl: 'data:image/jpeg;base64,xx' })} />)
    await user.click(await screen.findByRole('button', { name: 'Suggest to a friend' }))
    await user.click(await screen.findByRole('checkbox', { name: 'Ana' }))
    await user.type(screen.getByLabelText('Note (optional)'), '  Read it  ')
    await user.click(screen.getByRole('button', { name: 'Send' }))
    await waitFor(() => expect(api.suggestBook).toHaveBeenCalledTimes(1))
    const [sent, recipients, note] = vi.mocked(api.suggestBook).mock.calls[0]
    expect(sent.title).toBe('Stoner')
    expect(sent.coverUrl).toBeNull()
    expect(recipients).toEqual(['ana'])
    expect(note).toBe('Read it')
    expect(toast.show).toHaveBeenCalledWith('Suggested to Ana')
  })

  it('aFailedSendKeepsTheDialogOpen', async () => {
    const user = userEvent.setup()
    const api = fakeApi({
      friends: vi.fn(async () => [ana]),
      suggestBook: vi.fn(async () => { throw new CommunityError('offline', 'offline') }),
    })
    show(api, <BookCommunity book={book('Stoner')} />)
    await user.click(await screen.findByRole('button', { name: 'Suggest to a friend' }))
    await user.click(await screen.findByRole('checkbox', { name: 'Ana' }))
    await user.click(screen.getByRole('button', { name: 'Send' }))
    expect(await screen.findByRole('alert')).toHaveTextContent(/offline/i)
    expect(screen.getByRole('button', { name: 'Send' })).toBeEnabled()
  })

  it('theFeedOffersSuggestingABook', async () => {
    const user = userEvent.setup()
    const api = fakeApi({ friends: vi.fn(async () => [ana]), feed: vi.fn(async () => ({ entries: [entry()], next: null })) })
    show(api, <CommunityPage />)
    await user.click(await screen.findByRole('button', { name: 'Suggest Dune to a friend' }))
    expect(await screen.findByRole('checkbox', { name: 'Ana' })).toBeInTheDocument()
  })

  it('theBadgeCountsTheNewOnes', async () => {
    const api = withSuggestions(suggestion(2), suggestion(1, { seen: true }))
    show(api, <><FollowSuggestions /><SuggestionBadge /></>)
    expect(await screen.findByText('1 new book suggestion')).toBeInTheDocument()
  })

  it('theBadgeLooksAgainWhenTheAppComesBack', async () => {
    const api = withSuggestions()
    show(api, <><FollowSuggestions /><SuggestionBadge /></>)
    await waitFor(() => expect(api.bookSuggestions).toHaveBeenCalledTimes(1))
    vi.mocked(api.bookSuggestions).mockResolvedValue([suggestion(1), suggestion(2)])
    act(() => { window.dispatchEvent(new Event('focus')) })
    expect(await screen.findByText('2 new book suggestions')).toBeInTheDocument()
  })

  it('aRefreshUnderWayDoesNotBringBackADismissedOne', async () => {
    let answer: (rows: BookSuggestion[]) => void = () => {}
    const api = fakeApi({ bookSuggestions: vi.fn(async () => [suggestion(1)]) })
    const inbox = new SuggestionInbox(api)
    await inbox.refresh()
    vi.mocked(api.bookSuggestions).mockImplementation(() => new Promise((resolve) => { answer = resolve }))
    const refreshing = inbox.refresh()
    expect(await inbox.close(1)).toBe(true)
    answer([suggestion(1)])
    await refreshing
    expect(inbox.items()).toEqual([])
  })

  it('oneArrivingWhileMarkingSeenStaysNew', async () => {
    let answer: (rows: BookSuggestion[]) => void = () => {}
    const api = fakeApi({ bookSuggestions: vi.fn(async () => [suggestion(1)]) })
    const inbox = new SuggestionInbox(api)
    await inbox.refresh()
    vi.mocked(api.bookSuggestions).mockImplementation(() => new Promise((resolve) => { answer = resolve }))
    const refreshing = inbox.refresh()
    inbox.markSeen()
    expect(api.markSuggestionsSeen).toHaveBeenCalledWith([1])
    answer([suggestion(2), suggestion(1)])
    await refreshing
    expect(inbox.items().map((s) => s.id)).toEqual([2, 1])
    expect(inbox.unseen()).toBe(1)
  })

  it('aRefreshDuringACloseDoesNotBringItBack', async () => {
    let finish: () => void = () => {}
    const api = fakeApi({
      bookSuggestions: vi.fn(async () => [suggestion(1)]),
      closeSuggestion: vi.fn(() => new Promise<void>((resolve) => { finish = resolve })),
    })
    const inbox = new SuggestionInbox(api)
    await inbox.refresh()
    const closing = inbox.close(1)
    await inbox.refresh()
    expect(inbox.items()).toEqual([])
    finish()
    expect(await closing).toBe(true)
    expect(inbox.items()).toEqual([])
  })

  it('aFailedCloseDuringARefreshShowsItOnce', async () => {
    let fail: (e: Error) => void = () => {}
    const api = fakeApi({
      bookSuggestions: vi.fn(async () => [suggestion(1)]),
      closeSuggestion: vi.fn(() => new Promise<void>((_resolve, reject) => { fail = reject })),
    })
    const inbox = new SuggestionInbox(api)
    await inbox.refresh()
    const closing = inbox.close(1)
    await inbox.refresh()
    fail(new CommunityError('offline', 'offline'))
    expect(await closing).toBe(false)
    expect(inbox.items().map((s) => s.id)).toEqual([1])
  })

  it('anOlderAnswerDoesNotReplaceANewerOne', async () => {
    const answers: ((rows: BookSuggestion[]) => void)[] = []
    const api = fakeApi({ bookSuggestions: vi.fn(() => new Promise<BookSuggestion[]>((resolve) => { answers.push(resolve) })) })
    const inbox = new SuggestionInbox(api)
    const first = inbox.refresh()
    const second = inbox.refresh()
    answers[1]([suggestion(2), suggestion(1)])
    await second
    answers[0]([suggestion(1)])
    await first
    expect(inbox.items().map((s) => s.id)).toEqual([2, 1])
  })

  it('neverSendsHalfAnEmoji', async () => {
    const rpc = vi.fn(async () => ({ data: 1, error: null }))
    const real = createCommunityApi({ rpc } as unknown as Parameters<typeof createCommunityApi>[0], 'me')
    const broken = { ...suggestion(1).book, title: 'T\ud83d', authors: 'A\ude00', blurb: 'ok \ud83d\ude00 cut\ud83d' }
    await real.suggestBook(broken, ['ana'], 'note\ud83d')
    const args = (rpc.mock.calls as unknown as [string, { p_book: Record<string, string>; p_note: string }][])[0][1]
    expect([args.p_book.title, args.p_book.authors, args.p_book.blurb, args.p_note]).toEqual(['T', 'A', 'ok \ud83d\ude00 cut', 'note'])
  })

  it('theDailyLimitSaysSo', async () => {
    const rpc = vi.fn(async () => ({ data: null, error: { code: 'P0429', message: 'too many' } }))
    const real = createCommunityApi({ rpc } as unknown as Parameters<typeof createCommunityApi>[0], 'me')
    const failure = await real.suggestBook(suggestion(1).book, ['ana'], '').catch((e: unknown) => e)
    expect(communityMessage(failure)).toBe("You've suggested a lot of books today. Try again tomorrow.")
  })

  it('saysWhatHappenedToTheSuggestion', () => {
    expect(suggestionSentText(1, [ana])).toBe('Suggested to Ana')
    expect(suggestionSentText(2, [ana, rui])).toBe('Suggested to 2 friends')
    expect(suggestionSentText(1, [ana, rui])).toBe('Suggested to 1 of 2 friends')
    expect(suggestionSentText(0, [ana])).toBe("You've already suggested this to Ana")
    expect(suggestionSentText(0, [ana, rui])).toBe("You've already suggested this to them")
  })
})
