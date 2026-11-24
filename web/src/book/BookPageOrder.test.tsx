import 'fake-indexeddb/auto'
import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { CommunityProvider } from '../community/CommunityProvider'
import type { CommunityApi } from '../community/api'
import type { CommunityNav } from '../community/useCommunity'
import { rowKey, type SyncApi } from '../sync/api'
import type { Book } from '../sync/model'
import { LibraryStore } from '../sync/store'
import { BookPage } from './BookPage'

const sync: SyncApi = {
  pull: async () => ({ rows: [], last: 0 }),
  push: async (changes) => ({ applied: changes.map((c) => rowKey(c.table, c.id)), rejected: [] }),
  onPing: () => () => {},
}
const ana = { id: 'ana', username: 'ana', displayName: 'Ana', avatarUrl: null, visibility: 'friends' as const }
const known: Record<string, unknown> = {
  me: 'me',
  myProfile: async () => ({ ...ana, id: 'me', username: 'me', displayName: 'Me', shareActivity: true }),
  friendsWithBook: async () => [{ person: ana, status: 'READING', rating: null, progress: 40, review: '' }],
  bookScore: async () => ({ ratings: 1, average: 5, perScore: [1, 0, 0, 0, 0], reviews: 1 }),
  bookReviews: async () => [{ person: ana, rating: 5, review: 'Loved it', updatedAt: Date.now() }],
}
// Anything else the community asks for at start: nothing.
const api = new Proxy(known, {
  get: (target, key) => (key in target ? target[key as string] : key === 'then' ? undefined : async () => []),
}) as unknown as CommunityApi
const nav: CommunityNav = {
  personHref: (id) => `#${id}`, openPerson: () => {}, openBook: () => {}, openPeople: () => {}, openAccount: () => {}, openFeed: () => {},
}
const stoner: Book = {
  id: 'b1', isbn: '9781590171998', title: 'Stoner', authors: 'John Williams', publisher: null, publishedYear: null, pageCount: null,
  coverUrl: null, description: null, status: 'READ', rating: null, notes: '', dateAdded: 1, dateRead: null, dateStarted: null,
  currentPage: null, progressPercent: null, review: '', tags: [], series: null, seriesNumber: null, lentTo: null, lentOn: null,
  shelfId: null, shelfPosition: null, deleted: false,
}

describe('the order of the book page', () => {
  it('friendsWhoHaveThisBookIsTheLastSection', async () => {
    const store = new LibraryStore({ api: sync, owner: 'order-test', pollMs: 0, retryDelays: [60_000] })
    await store.load()
    store.create(stoner)
    render(
      <CommunityProvider api={api} nav={nav}>
        <BookPage store={store} owner="me" id="b1" onBack={() => {}} />
      </CommunityProvider>,
    )
    const friends = await screen.findByRole('heading', { name: 'Friends who have this book' })
    const readers = await screen.findByRole('heading', { name: 'What readers say' })
    const sections = [...document.querySelectorAll('.book-main section, .book-main fieldset')]
    expect(sections.at(-1)).toBe(friends.closest('section'))
    // The readers' reviews stay where they were, before the cover.
    const cover = screen.getByRole('region', { name: 'Cover' })
    expect(readers.compareDocumentPosition(cover) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    await store.close()
  })
})
