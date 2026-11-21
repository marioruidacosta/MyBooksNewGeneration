import 'fake-indexeddb/auto'
import { IDBFactory } from 'fake-indexeddb'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mergeBooks } from '../tidy/mergeBooks'
import { applyPlan, type Plan } from '../io/importPlan'
import type { PullPage, PushResult, SyncApi } from '../sync/api'
import type { Book, SyncRow } from '../sync/model'
import { openDB } from 'idb'
import { databaseName } from '../sync/queue'
import { LibraryStore } from '../sync/store'
import { Publisher, publicFieldsDiffer, publicState, type RpcClient } from './publisher'

const T = 5_000

function book(id: string, over: Partial<Book> = {}): Book {
  return {
    id, isbn: null, title: `Book ${id}`, authors: 'Author', publisher: null, publishedYear: null, pageCount: null,
    coverUrl: null, description: null, status: 'TO_READ', rating: null, notes: '', dateAdded: 7, dateRead: null,
    dateStarted: null, currentPage: null, progressPercent: null, review: '', tags: [], series: null,
    seriesNumber: null, lentTo: null, lentOn: null, deleted: false, ...over,
  }
}

/** A cloud library the store syncs with; `rows` is what the server would hand out on the next pull. */
class FakeApi implements SyncApi {
  rows: SyncRow[] = []
  async pull(after: number): Promise<PullPage> {
    const rows = this.rows.filter((r) => (r.seq ?? 0) > after)
    return { rows, last: rows.length ? Math.max(...rows.map((r) => r.seq ?? 0)) : after }
  }
  async push(): Promise<PushResult> {
    return { applied: [], rejected: [] }
  }
  onPing(): () => void {
    return () => {}
  }
}

type Call = { fn: string; args: Record<string, unknown> }

/** The Supabase client as the publisher uses it: records each call; `shared` is what my_shared_book_ids returns. */
class FakeRpc implements RpcClient {
  calls: Call[] = []
  shared: string[] = []
  /** Errors for the next publish/unpublish calls, in order (null: it works). */
  failures: ({ message: string; code?: string } | Error | null)[] = []
  idsError = false
  /** While set, publish_book waits for it before answering. */
  gate: Promise<void> | null = null
  /** While set, my_shared_book_ids waits for it before answering. */
  idsGate: Promise<void> | null = null

  async rpc(fn: string, args: Record<string, unknown> = {}) {
    this.calls.push({ fn, args })
    if (fn === 'my_shared_book_ids') {
      if (this.idsGate) await this.idsGate
      return this.idsError ? { data: null, error: { message: 'offline' } } : { data: [...this.shared], error: null }
    }
    if (fn === 'publish_book' && this.gate) await this.gate
    const failure = this.failures.shift() ?? null
    if (failure instanceof Error) throw failure
    if (!failure && fn === 'publish_book') {
      const id = (args.book as { id: string }).id
      if (!this.shared.includes(id)) this.shared.push(id)
    }
    if (!failure && fn === 'unpublish_book') this.shared = this.shared.filter((s) => s !== args.id)
    return { data: null, error: failure }
  }
  of(fn: string) {
    return this.calls.filter((c) => c.fn === fn)
  }
}

const stores: LibraryStore[] = []
const publishers: Publisher[] = []

async function setup(
  opts: { shared?: string[]; books?: Book[]; retryDelays?: number[]; owner?: string; currentOwner?: () => string | null | 'unknown' | Promise<string | null | 'unknown'> } = {},
) {
  const api = new FakeApi()
  const rpc = new FakeRpc()
  rpc.shared = opts.shared ?? []
  const store = new LibraryStore({ api, owner: 'u1', pollMs: 0, retryDelays: [60_000] })
  stores.push(store)
  await store.load()
  for (const b of opts.books ?? []) store.create(b)
  const publisher = new Publisher({
    store, client: rpc, debounceMs: 0, retryDelays: opts.retryDelays ?? [5], owner: opts.owner ?? 'u1', currentOwner: opts.currentOwner ?? (() => 'u1'),
  })
  publishers.push(publisher)
  await publisher.start()
  return { api, rpc, store, publisher }
}

beforeEach(() => {
  globalThis.indexedDB = new IDBFactory()
})

afterEach(async () => {
  publishers.splice(0).forEach((p) => p.stop())
  await Promise.all(stores.splice(0).map((s) => s.close()))
})

describe('publicState (PublicStateTest.kt)', () => {
  it('carriesPagesAndBlurb', () => {
    const b = book('s', { title: 'A Relíquia', status: 'READING', pageCount: 344, currentPage: 182, description: 'Teodorico.' })
    const state = publicState(b)
    expect(state.pages).toBe(344)
    expect(state.current_page).toBe(182)
    expect(state.blurb).toBe('Teodorico.')
    expect(state.status).toBe('reading')
    expect(state.id).toBe('s')
    expect(publicFieldsDiffer(b, { ...b, currentPage: 183 })).toBe(true)
    expect(publicFieldsDiffer(b, { ...b, description: 'Outra.' })).toBe(true)
  })

  it('blurbIsCollapsedAndCut', () => {
    expect(publicState(book('s', { description: 'Uma\n\tsátira  deliciosa ' })).blurb).toBe('Uma sátira deliciosa')
    expect(publicState(book('s', { description: 'a'.repeat(700) })).blurb).toHaveLength(600)
    expect(publicState(book('s', { description: '  \n ' })).blurb).toBeNull()
    expect(publicState(book('s', { description: null })).blurb).toBeNull()
  })

  it('titleAuthorsAndCoverLimits', () => {
    const s = publicState(book('s', { title: 'x'.repeat(400), authors: 'y'.repeat(400), coverUrl: 'http://a/b.jpg' }))
    expect(s.title).toHaveLength(300)
    expect(s.authors).toHaveLength(300)
    expect(s.cover_url).toBeNull() // only https covers
    expect(publicState(book('s', { coverUrl: 'photo' })).cover_url).toBeNull() // a photo from the app is a local file
    expect(publicState(book('s', { coverUrl: 'https://a/b.jpg' })).cover_url).toBe('https://a/b.jpg')
  })

  it('statusNamesAndProgress', () => {
    const states = (['READING', 'TO_READ', 'READ', 'WISHLIST'] as const).map((st) => publicState(book('s', { status: st })).status)
    expect(states).toEqual(['reading', 'to_read', 'read', 'to_buy'])
    expect(publicState(book('s', { progressPercent: 40, rating: 4, review: 'Bom', isbn: '978' }))).toMatchObject({
      progress: 40, rating: 4, review: 'Bom', isbn: '978',
    })
  })

  it('notesAndDatesDontCount', () => {
    const b = book('s')
    expect(publicFieldsDiffer(b, { ...b, notes: 'x', dateRead: 5, tags: ['a'], lentTo: 'Ana', dateStarted: 3 })).toBe(false)
    for (const change of [
      { status: 'READ' as const }, { rating: 3 }, { progressPercent: 10 }, { review: 'r' }, { title: 't' },
      { authors: 'a' }, { coverUrl: 'https://x' }, { pageCount: 9 },
    ]) {
      expect(publicFieldsDiffer(b, { ...b, ...change })).toBe(true)
    }
  })
})

describe('the publisher', () => {
  it('aChangeToASharedBookPublishesOnce', async () => {
    const { rpc, store, publisher } = await setup({ shared: ['b1'], books: [book('b1')] })
    const before = store.book('b1')!
    store.edit('books', 'b1', { status: 'READING' })
    store.edit('books', 'b1', { rating: 4 })
    await publisher.idle()
    const sent = rpc.of('publish_book')
    expect(sent).toHaveLength(1)
    expect(sent[0].args.book).toEqual(publicState({ ...before, status: 'READING', rating: 4 }))
    expect(sent[0].args.before).toEqual(publicState(before))
    // The shared ids are read at load and again after the publish.
    // ...and at the start of the send, so a phone's exclusion made meanwhile is seen.
    expect(rpc.of('my_shared_book_ids')).toHaveLength(3)
  }, T)

  it('anUnsharedBookIsNeverPublished', async () => {
    const { rpc, store, publisher } = await setup({ shared: ['other'], books: [book('b1')] })
    store.edit('books', 'b1', { status: 'READ', rating: 5 })
    store.remove('books', 'b1')
    await publisher.idle()
    expect(rpc.of('publish_book')).toHaveLength(0)
    expect(rpc.of('unpublish_book')).toHaveLength(0)
    expect(publisher.isShared('b1')).toBe(false)
  }, T)

  it('notesOnlyChangesDontPublish', async () => {
    const { rpc, store, publisher } = await setup({ shared: ['b1'], books: [book('b1')] })
    store.edit('books', 'b1', { notes: 'a note', tags: ['x'], date_read: 99, lent_to: 'Ana' })
    await publisher.idle()
    expect(rpc.of('publish_book')).toHaveLength(0)
  }, T)

  it('pulledChangesNeverPublish', async () => {
    const { api, rpc, store, publisher } = await setup({ shared: ['b1'], books: [book('b1')] })
    api.rows.push({
      table: 'books', id: 'b1', seq: 1, fields: { status: 'READ', rating: 5, title: 'Renamed' },
      times: { status: 9e12, rating: 9e12, title: 9e12 },
    })
    await store.refresh()
    expect(store.book('b1')?.status).toBe('READ')
    await publisher.idle()
    expect(rpc.of('publish_book')).toHaveLength(0)
  }, T)

  it('deletingASharedBookUnpublishes', async () => {
    const { rpc, store, publisher } = await setup({ shared: ['b1'], books: [book('b1')] })
    store.remove('books', 'b1')
    await publisher.idle()
    expect(rpc.of('unpublish_book')).toEqual([{ fn: 'unpublish_book', args: { id: 'b1' } }])
    expect(publisher.isShared('b1')).toBe(false)
  }, T)

  it('aBookAddedOnTheSitePublishes', async () => {
    const { rpc, store, publisher } = await setup()
    store.create(book('new', { title: 'Novo' }))
    await publisher.idle()
    const sent = rpc.of('publish_book')
    expect(sent).toHaveLength(1)
    expect((sent[0].args.book as { title: string }).title).toBe('Novo')
    expect(sent[0].args.before).toBeNull()
    // The server now lists it, and a later change publishes too.
    expect(publisher.isShared('new')).toBe(true)
    store.edit('books', 'new', { status: 'READING' })
    await publisher.idle()
    expect(rpc.of('publish_book')).toHaveLength(2)
  }, T)

  it('importingIsNotPublishing', async () => {
    const { rpc, store, publisher } = await setup({ shared: ['b1'], books: [book('b1')] })
    const plan = {
      added: [book('imp1'), book('imp2')],
      merged: [{ id: 'b1', changes: { status: 'READ', rating: 5 } }],
      quotes: [], goals: [], uploads: [],
    } as unknown as Plan
    await applyPlan(plan, store, {})
    await publisher.idle()
    expect(store.book('b1')?.status).toBe('READ')
    expect(store.book('imp1')).toBeDefined()
    expect(rpc.of('publish_book')).toHaveLength(0)
    // A book imported here is not "created on the site": editing it later doesn't publish either.
    store.edit('books', 'imp1', { status: 'READ' })
    await publisher.idle()
    expect(rpc.of('publish_book')).toHaveLength(0)
  }, T)

  it('tidyMergeUnpublishesTheDroppedBookAndPublishesTheKeptOnce', async () => {
    const keep = book('k', { title: 'Same', isbn: null, pageCount: null })
    const drop = book('d', { title: 'Same', pageCount: 300, status: 'READING', progressPercent: 20 })
    const { rpc, store, publisher } = await setup({ shared: ['k', 'd'], books: [keep, drop] })
    await mergeBooks(store, store.book('k')!, store.book('d')!)
    await publisher.idle()
    expect(rpc.of('unpublish_book').map((c) => c.args.id)).toEqual(['d'])
    expect(rpc.of('publish_book')).toHaveLength(1)
    expect((rpc.of('publish_book')[0].args.book as { id: string }).id).toBe('k')
  }, T)

  it('tidyMergeOfUnsharedBooksPublishesNothing', async () => {
    const { rpc, store, publisher } = await setup({
      shared: [], books: [book('k', { title: 'Same' }), book('d', { title: 'Same', pageCount: 300 })],
    })
    await mergeBooks(store, store.book('k')!, store.book('d')!)
    await publisher.idle()
    expect(rpc.calls.filter((c) => c.fn !== 'my_shared_book_ids')).toEqual([])
  }, T)

  it('undoingADeleteOfAnExcludedBookPublishesNothing', async () => {
    const { rpc, store, publisher } = await setup({ shared: [], books: [book('b1')] })
    const times = store.bookTimes('b1')
    const gone = store.book('b1')!
    store.remove('books', 'b1')
    await publisher.idle()
    store.restoreBook(gone, times)
    await publisher.idle()
    expect(rpc.calls.filter((c) => c.fn !== 'my_shared_book_ids')).toEqual([])
  }, T)

  it('undoingADeleteBeforeTheUnpublishLeavesALLAsItWas', async () => {
    const { rpc, store, publisher } = await setup({ shared: ['b1'], books: [book('b1')] })
    // Hold the unpublish back: it is still pending when Undo comes.
    const slow = new Publisher({
      store, client: rpc, debounceMs: 60_000, owner: 'u1', currentOwner: () => 'u1',
    })
    publishers.push(slow)
    publisher.stop()
    await slow.start()
    const before = rpc.calls.length
    const gone = store.book('b1')!
    const times = store.bookTimes('b1')
    store.remove('books', 'b1')
    store.restoreBook(gone, times)
    expect(slow.hasPending()).toBe(false)
    expect(rpc.calls.slice(before).filter((c) => c.fn !== 'my_shared_book_ids')).toEqual([])
    expect(slow.isShared('b1')).toBe(true)
  }, T)

  it('undoingAMergeDoesNotRepublishTheDroppedCopy', async () => {
    const keep = book('k', { title: 'Same' })
    const drop = book('d', { title: 'Same', pageCount: 300, status: 'READING', progressPercent: 20 })
    const { rpc, store, publisher } = await setup({ shared: ['k', 'd'], books: [keep, drop] })
    const { undo } = await mergeBooks(store, store.book('k')!, store.book('d')!)
    await publisher.idle()
    expect(rpc.of('unpublish_book').map((c) => c.args.id)).toEqual(['d'])
    const published = rpc.of('publish_book').length
    undo()
    await publisher.idle()
    expect(rpc.of('publish_book').filter((c) => (c.args.book as { id: string }).id === 'd')).toEqual([])
    expect(rpc.of('publish_book').length).toBeGreaterThanOrEqual(published)
  }, T)

  it('aFailedPublishIsRetriedAndNeverUndoesTheEdit', async () => {
    const { rpc, store, publisher } = await setup({ shared: ['b1'], books: [book('b1')] })
    rpc.failures = [{ message: 'offline' }, new Error('network down')]
    store.edit('books', 'b1', { status: 'READING' })
    await publisher.idle()
    expect(rpc.of('publish_book')).toHaveLength(3)
    expect(store.book('b1')?.status).toBe('READING')
    // The retry sends the book as it was before the edit, as the first try did.
    const last = rpc.of('publish_book')[2].args
    expect((last.before as { status: string }).status).toBe('to_read')
    expect((last.book as { status: string }).status).toBe('reading')
  }, T)

  it('aRefusedBookIsDroppedNotRetried', async () => {
    const { rpc, store, publisher } = await setup({ shared: ['b1'], books: [book('b1')] })
    rpc.failures = [{ message: 'invalid book', code: '22023' }]
    store.edit('books', 'b1', { status: 'READING' })
    await publisher.idle()
    expect(rpc.of('publish_book')).toHaveLength(1)
    expect(store.book('b1')?.status).toBe('READING')
  }, T)

  it('editsMadeBeforeTheSharedIdsAreKnownArePublishedOnceTheyAre', async () => {
    const api = new FakeApi()
    const rpc = new FakeRpc()
    rpc.shared = ['b1']
    rpc.idsError = true
    const store = new LibraryStore({ api, owner: 'u1', pollMs: 0, retryDelays: [60_000] })
    stores.push(store)
    await store.load()
    store.create(book('b1'))
    const publisher = new Publisher({ store, client: rpc, debounceMs: 0, retryDelays: [5], owner: 'u1', currentOwner: () => 'u1' })
    publishers.push(publisher)
    await publisher.start()
    store.edit('books', 'b1', { status: 'READ' })
    rpc.idsError = false
    await publisher.idle()
    expect(rpc.of('publish_book')).toHaveLength(1)
  }, T)

  it('aBookExcludedOnThePhoneAfterLoadIsNotPublished', async () => {
    const { rpc, store, publisher } = await setup({ shared: ['b1'], books: [book('b1')] })
    expect(publisher.isShared('b1')).toBe(true)
    rpc.shared = [] // the person excluded it on the phone while this tab was open
    store.edit('books', 'b1', { status: 'READ' })
    await publisher.idle()
    expect(rpc.of('publish_book')).toHaveLength(0)
    expect(publisher.isShared('b1')).toBe(false)
  }, T)

  it('aBookSharedOnThePhoneAfterLoadIsPublished', async () => {
    const { rpc, store, publisher } = await setup({ shared: [], books: [book('b1')] })
    rpc.shared = ['b1']
    store.edit('books', 'b1', { status: 'READ' })
    await publisher.idle()
    expect(rpc.of('publish_book')).toHaveLength(1)
  }, T)

  it('aFailedIdsReadKeepsTheChangeWaitingInsteadOfDecidingFromAStaleSet', async () => {
    const { rpc, store, publisher } = await setup({ shared: ['b1'], books: [book('b1')] })
    rpc.idsError = true
    store.edit('books', 'b1', { status: 'READ' })
    await new Promise((r) => setTimeout(r, 30))
    expect(rpc.of('publish_book')).toHaveLength(0)
    expect(publisher.hasPending()).toBe(true)
    rpc.idsError = false
    await publisher.idle()
    expect(rpc.of('publish_book')).toHaveLength(1)
  }, T)

  it('deletingDuringTheFirstPublishUnpublishes', async () => {
    const { rpc, store, publisher } = await setup()
    let release!: () => void
    rpc.gate = new Promise<void>((r) => (release = r))
    store.create(book('new'))
    while (rpc.of('publish_book').length === 0) await new Promise((r) => setTimeout(r, 1))
    store.remove('books', 'new')
    release()
    await publisher.idle()
    expect(rpc.of('unpublish_book').map((c) => c.args.id)).toEqual(['new'])
    expect(rpc.shared).toEqual([])
  }, T)

  it('aSendNeverGoesOutUnderAnotherAccount', async () => {
    let user: string | null = 'u1'
    const { rpc, store, publisher } = await setup({
      shared: ['b1'], books: [book('b1')], owner: 'u1', currentOwner: () => user,
    })
    store.edit('books', 'b1', { status: 'READ' })
    await publisher.idle()
    expect(rpc.of('publish_book')).toHaveLength(1)
    user = 'u2'
    store.edit('books', 'b1', { status: 'READING' })
    store.remove('books', 'b1')
    await publisher.idle()
    expect(rpc.of('publish_book')).toHaveLength(1)
    expect(rpc.of('unpublish_book')).toHaveLength(0)
  }, T)

  it('sharingIsUnknownUntilIdsLoad', async () => {
    const api = new FakeApi()
    const rpc = new FakeRpc()
    rpc.idsError = true
    const store = new LibraryStore({ api, owner: 'u1', pollMs: 0, retryDelays: [60_000] })
    stores.push(store)
    await store.load()
    const publisher = new Publisher({ store, client: rpc, debounceMs: 0, retryDelays: [5], owner: 'u1', currentOwner: () => 'u1' })
    publishers.push(publisher)
    await publisher.start()
    expect(publisher.isShared('b1')).toBeNull()
    rpc.idsError = false
    rpc.shared = ['b1']
    await vi.waitFor(() => expect(publisher.isShared('b1')).toBe(true))
    expect(publisher.isShared('zz')).toBe(false)
  }, T)

  it('aRefusedBookIsRetriedWhenItChanges', async () => {
    const { rpc, store, publisher } = await setup()
    rpc.failures = [{ message: 'invalid book', code: '22023' }]
    store.create(book('new', { title: 'Novo' }))
    await publisher.idle()
    expect(rpc.of('publish_book')).toHaveLength(1)
    // Nothing the community sees changed: still not sent.
    store.edit('books', 'new', { notes: 'a note' })
    await publisher.idle()
    expect(rpc.of('publish_book')).toHaveLength(1)
    // A change to what it sees: sent again.
    store.edit('books', 'new', { title: 'Novo, corrigido' })
    await publisher.idle()
    expect(rpc.of('publish_book')).toHaveLength(2)
    expect(publisher.isShared('new')).toBe(true)
  }, T)

  it('stoppingDuringTheSessionCheckSendsNothing', async () => {
    let release!: () => void
    const held = new Promise<void>((r) => (release = r))
    let checks = 0
    const { rpc, store, publisher } = await setup({
      shared: ['b1'], books: [book('b1')],
      currentOwner: async () => {
        if (++checks > 0) await held
        return 'u1'
      },
    })
    const before = rpc.calls.length
    store.edit('books', 'b1', { status: 'READ' })
    while (checks === 0) await new Promise((r) => setTimeout(r, 1))
    publisher.stop() // an account switch, while the session is being read
    release()
    await new Promise((r) => setTimeout(r, 30))
    expect(rpc.calls.length).toBe(before)
  }, T)

  it('aSessionErrorKeepsTheQueue', async () => {
    let session: string | null | 'unknown' = 'unknown'
    const { rpc, store, publisher } = await setup({
      shared: ['b1'], books: [book('b1')], currentOwner: () => session,
    })
    store.edit('books', 'b1', { status: 'READ' })
    await new Promise((r) => setTimeout(r, 30))
    expect(rpc.of('publish_book')).toHaveLength(0)
    expect(publisher.hasPending()).toBe(true)
    session = 'u1' // back online
    await publisher.idle()
    expect(rpc.of('publish_book')).toHaveLength(1)
  }, T)

  it('signedOutDropsTheQueue', async () => {
    const { rpc, store, publisher } = await setup({
      shared: ['b1'], books: [book('b1')], currentOwner: () => null,
    })
    store.edit('books', 'b1', { status: 'READ' })
    await new Promise((r) => setTimeout(r, 30))
    expect(rpc.of('publish_book')).toHaveLength(0)
    expect(publisher.hasPending()).toBe(false)
  }, T)

  it('withoutAnOwnerNothingIsSent', async () => {
    const api = new FakeApi()
    const rpc = new FakeRpc()
    rpc.shared = ['b1']
    const store = new LibraryStore({ api, owner: 'u1', pollMs: 0, retryDelays: [60_000] })
    stores.push(store)
    await store.load()
    store.create(book('b1'))
    const publisher = new Publisher({ store, client: rpc, debounceMs: 0, retryDelays: [5] } as never)
    publishers.push(publisher)
    await publisher.start()
    store.edit('books', 'b1', { status: 'READ' })
    await new Promise((r) => setTimeout(r, 30))
    expect(rpc.of('publish_book')).toHaveLength(0)
  }, T)

  it('deletingAfterAnEditDuringTheFirstPublishUnpublishes', async () => {
    const { rpc, store, publisher } = await setup()
    let release!: () => void
    rpc.gate = new Promise<void>((r) => (release = r))
    store.create(book('new'))
    while (rpc.of('publish_book').length === 0) await new Promise((r) => setTimeout(r, 1))
    store.edit('books', 'new', { title: 'Edited meanwhile' })
    let releaseIds!: () => void
    rpc.idsGate = new Promise<void>((r) => (releaseIds = r))
    const idReads = rpc.of('my_shared_book_ids').length
    release()
    // The first publish is done; the pass is reading the shared ids before the edit goes out.
    while (rpc.of('my_shared_book_ids').length === idReads) await new Promise((r) => setTimeout(r, 1))
    store.remove('books', 'new')
    releaseIds()
    await publisher.idle()
    expect(rpc.of('unpublish_book').map((c) => c.args.id)).toEqual(['new'])
    expect(rpc.shared).toEqual([])
  }, T)

  it('aSuccessfulSendClearsTheRefusal', async () => {
    const { rpc, store, publisher } = await setup()
    let release!: () => void
    rpc.gate = new Promise<void>((r) => (release = r))
    rpc.failures = [{ message: 'invalid book', code: '22023' }]
    store.create(book('new', { title: 'A' }))
    while (rpc.of('publish_book').length === 0) await new Promise((r) => setTimeout(r, 1))
    store.edit('books', 'new', { title: 'B' }) // goes out after the refusal, and works
    release()
    await publisher.idle()
    expect(rpc.of('publish_book')).toHaveLength(2)
    // Back to what was refused: that is a change from B, and the old refusal must not hide it.
    store.edit('books', 'new', { title: 'A' })
    await publisher.idle()
    expect(rpc.of('publish_book')).toHaveLength(3)
  }, T)
  it('pendingPublishesSurviveAReload', async () => {
    const api = new FakeApi()
    const rpc = new FakeRpc()
    rpc.shared = ['b1']
    const first = new LibraryStore({ api, owner: 'u1', pollMs: 0, retryDelays: [60_000] })
    stores.push(first)
    await first.load()
    first.create(book('b1'))
    const p1 = new Publisher({ store: first, client: rpc, debounceMs: 0, retryDelays: [60_000], owner: 'u1', currentOwner: () => 'u1' })
    publishers.push(p1)
    await p1.start()
    // The send fails (offline), and the tab is closed with the change still waiting.
    rpc.failures = [new Error('offline')]
    first.edit('books', 'b1', { status: 'READ' })
    while (rpc.of('publish_book').length === 0) await new Promise((r) => setTimeout(r, 1))
    p1.stop()
    await first.close()

    // The next visit of the same account sends it, from the state before the change.
    const second = new LibraryStore({ api, owner: 'u1', pollMs: 0, retryDelays: [60_000] })
    stores.push(second)
    await second.load()
    const p2 = new Publisher({ store: second, client: rpc, debounceMs: 0, retryDelays: [5], owner: 'u1', currentOwner: () => 'u1' })
    publishers.push(p2)
    await p2.start()
    expect(p2.hasPending()).toBe(true)
    await p2.idle()
    const sent = rpc.of('publish_book')
    expect(sent).toHaveLength(2)
    expect((sent[1].args.book as { status: string }).status).toBe('read')
    expect((sent[1].args.before as { status: string }).status).toBe('to_read')
    expect(p2.hasPending()).toBe(false)
    await second.idle()

    // Sent: nothing is kept for a third visit.
    p2.stop()
    await second.close()
    const third = new LibraryStore({ api, owner: 'u1', pollMs: 0, retryDelays: [60_000] })
    stores.push(third)
    await third.load()
    const p3 = new Publisher({ store: third, client: rpc, debounceMs: 0, retryDelays: [5], owner: 'u1', currentOwner: () => 'u1' })
    publishers.push(p3)
    await p3.start()
    expect(p3.hasPending()).toBe(false)
  }, T)

  it('pendingPublishesOfAnotherAccountAreDropped', async () => {
    const api = new FakeApi()
    const rpc = new FakeRpc()
    rpc.shared = ['b1']
    const first = new LibraryStore({ api, owner: 'u1', pollMs: 0, retryDelays: [60_000] })
    stores.push(first)
    await first.load()
    first.create(book('b1'))
    const p1 = new Publisher({ store: first, client: rpc, debounceMs: 0, retryDelays: [60_000], owner: 'u1', currentOwner: () => 'u1' })
    publishers.push(p1)
    await p1.start()
    rpc.failures = [new Error('offline')]
    first.edit('books', 'b1', { status: 'READ' })
    while (rpc.of('publish_book').length === 0) await new Promise((r) => setTimeout(r, 1))
    p1.stop()
    await first.close()

    // The same browser database, but another account is signed in now: nothing is sent, nothing is kept.
    const second = new LibraryStore({ api, owner: 'u1', pollMs: 0, retryDelays: [60_000] })
    stores.push(second)
    await second.load()
    const p2 = new Publisher({ store: second, client: rpc, debounceMs: 0, retryDelays: [5], owner: 'u2', currentOwner: () => 'u2' })
    publishers.push(p2)
    await p2.start()
    expect(p2.hasPending()).toBe(false)
    await new Promise((r) => setTimeout(r, 30))
    expect(rpc.of('publish_book')).toHaveLength(1)
    p2.stop()
    await second.close()
    const third = new LibraryStore({ api, owner: 'u1', pollMs: 0, retryDelays: [60_000] })
    stores.push(third)
    await third.load()
    const p3 = new Publisher({ store: third, client: rpc, debounceMs: 0, retryDelays: [5], owner: 'u1', currentOwner: () => 'u1' })
    publishers.push(p3)
    await p3.start()
    expect(p3.hasPending()).toBe(false)
  }, T)

  /** A tab: a store and a publisher on the shared browser database. */
  async function tab(rpc: FakeRpc, api: FakeApi, owner = 'u1') {
    const store = new LibraryStore({ api, owner, pollMs: 0, retryDelays: [60_000] })
    stores.push(store)
    await store.load()
    const publisher = new Publisher({ store, client: rpc, debounceMs: 0, retryDelays: [60_000], owner, currentOwner: () => owner })
    publishers.push(publisher)
    return { store, publisher }
  }

  /** A first tab that leaves a failed post for b1 (to_read -> read) waiting, then is closed. */
  async function leaveOnePending(rpc: FakeRpc, api: FakeApi) {
    rpc.shared = ['b1']
    const first = await tab(rpc, api)
    first.store.create(book('b1'))
    await first.publisher.start()
    rpc.failures = [new Error('offline')]
    first.store.edit('books', 'b1', { status: 'READ' })
    while (rpc.of('publish_book').length === 0) await new Promise((r) => setTimeout(r, 1))
    first.publisher.stop()
    await first.store.close()
  }

  it('aRestoredPublishForADeletedBookIsDropped', async () => {
    const api = new FakeApi()
    const rpc = new FakeRpc()
    await leaveOnePending(rpc, api)
    // The phone deleted the book while the tab was closed.
    const second = await tab(rpc, api)
    second.store.remove('books', 'b1')
    await second.publisher.start()
    expect(second.publisher.hasPending()).toBe(false)
    await new Promise((r) => setTimeout(r, 30))
    expect(rpc.of('publish_book')).toHaveLength(1)
    expect(rpc.of('unpublish_book')).toHaveLength(0)
  }, T)

  it('aRestoredPublishSendsTheCurrentBook', async () => {
    const api = new FakeApi()
    const rpc = new FakeRpc()
    await leaveOnePending(rpc, api)
    // Changed again (on the phone) while the tab was closed: the post carries what the book is now.
    const second = await tab(rpc, api)
    second.store.edit('books', 'b1', { status: 'READING', rating: 4 })
    await second.publisher.start()
    await second.publisher.idle()
    const sent = rpc.of('publish_book')
    expect(sent).toHaveLength(2)
    expect((sent[1].args.book as { status: string }).status).toBe('reading')
    expect((sent[1].args.book as { rating: number }).rating).toBe(4)
    expect((sent[1].args.before as { status: string }).status).toBe('to_read')
  }, T)

  it('aRestoredPublishThatNoLongerDiffersSendsNothing', async () => {
    const api = new FakeApi()
    const rpc = new FakeRpc()
    await leaveOnePending(rpc, api)
    const second = await tab(rpc, api)
    second.store.edit('books', 'b1', { status: 'TO_READ' })
    await second.publisher.start()
    await second.publisher.idle()
    expect(rpc.of('publish_book')).toHaveLength(1)
    expect(second.publisher.hasPending()).toBe(false)
  }, T)

  async function storedPublishes() {
    const raw = await openDB(databaseName('u1'), 2)
    try {
      return await raw.getAll('publishes')
    } finally {
      raw.close()
    }
  }

  it('twoTabsKeepEachOthersPublishes', async () => {
    const api = new FakeApi()
    const rpc = new FakeRpc()
    rpc.failures = [new Error('offline'), new Error('offline')]
    const a = await tab(rpc, api)
    const b = await tab(rpc, api)
    await a.publisher.start()
    await b.publisher.start()
    a.store.create(book('b1'))
    b.store.create(book('b2'))
    while (rpc.of('publish_book').length < 2) await new Promise((r) => setTimeout(r, 1))
    // Each tab writes its kept publish after its own failed call, in its own time: wait for both rather than a fixed pause.
    await vi.waitFor(async () => {
      expect((await storedPublishes()).map((k) => k.id).sort()).toEqual(['b1', 'b2'])
    })
  }, T)

  it('aClosedTabsPublishesAreAdoptedOnce', async () => {
    const api = new FakeApi()
    const rpc = new FakeRpc()
    rpc.shared = ['b1']
    const a = await tab(rpc, api)
    a.store.create(book('b1'))
    await a.publisher.start()
    rpc.failures = [new Error('offline')]
    a.store.edit('books', 'b1', { status: 'READ' })
    while (rpc.of('publish_book').length === 0) await new Promise((r) => setTimeout(r, 1))
    // The tab dies without closing (no clean goodbye); minutes pass.
    const later = Date.now() + 3 * 60_000
    const now = vi.spyOn(Date, 'now').mockImplementation(() => later)
    try {
      const b = await tab(rpc, api)
      const c = await tab(rpc, api)
      await Promise.all([b.publisher.start(), c.publisher.start()])
      await Promise.all([b.publisher.idle(), c.publisher.idle()])
      expect(rpc.of('publish_book')).toHaveLength(2) // the failed one, and one adoption
      expect(await storedPublishes()).toHaveLength(0)
    } finally {
      now.mockRestore()
    }
  }, T)

  it('anUnsharedEditDoesNotWarnOnClose', async () => {
    const { rpc, store, publisher } = await setup({ shared: ['b1'], books: [book('b1'), book('b2')] })
    // b2 is not shared: its edit will never be posted, so closing the tab loses nothing.
    store.edit('books', 'b2', { status: 'READ' })
    expect(publisher.hasPending()).toBe(false)
    // b1 is shared: closing now would lose the post.
    rpc.idsError = true
    store.edit('books', 'b1', { status: 'READ' })
    expect(publisher.hasPending()).toBe(true)
    // A book made on the site counts too.
    store.create(book('new'))
    expect(publisher.hasPending()).toBe(true)
  }, T)
})
