import 'fake-indexeddb/auto'
import { IDBFactory } from 'fake-indexeddb'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import {
  rowKey, SyncError, syncErrorOf, type Change, type PullPage, type PushResult, type SyncApi,
} from './api'
import { mergeFields } from './merge'
import type { Book, SyncRow, SyncTable } from './model'
import { LibraryStore, type SaveStatus } from './store'

const T = 5_000 // every async test fails fast instead of hanging

/** A server that merges like `sync_push` and pages like `sync_pull`. */
class FakeServer implements SyncApi {
  rows = new Map<string, SyncRow>()
  seq = 0
  pulls: [number, number][] = []
  pushes: Change[][] = []
  /** Thrown by every push while set. */
  pushError: Error | null = null
  /** Thrown once each, in order, by the next pushes. */
  pushErrors: Error[] = []
  /** Thrown by every pull while set. */
  pullError: Error | null = null
  /** Rows the server sets aside, with the reason. */
  setAside = new Map<string, string>()
  /** Batches holding any of these rows are refused as a whole (a 4xx), as the server refuses a value it can't parse. */
  refuseWhole = new Set<string>()
  /** When set, the next pull waits for this before answering. */
  holdPull: Promise<void> | null = null
  private pingers: (() => void)[] = []

  /** A write from a phone: merged into the server's row, newest field wins. */
  write(table: SyncTable, id: string, fields: Record<string, unknown>, times: Record<string, number>) {
    const key = rowKey(table, id)
    const merged = mergeFields(this.rows.get(key), { table, id, fields, times })
    this.rows.set(key, { ...merged, seq: ++this.seq })
  }

  async pull(after: number, max: number): Promise<PullPage> {
    this.pulls.push([after, max])
    const hold = this.holdPull
    this.holdPull = null
    if (hold) await hold
    if (this.pullError) throw this.pullError
    const rows = [...this.rows.values()]
      .filter((r) => (r.seq ?? 0) > after)
      .sort((a, b) => (a.seq ?? 0) - (b.seq ?? 0))
      .slice(0, max)
      .map((r) => structuredClone(r))
    return { rows, last: rows.length ? rows[rows.length - 1].seq ?? after : after }
  }

  async push(changes: Change[]): Promise<PushResult> {
    this.pushes.push(structuredClone(changes))
    if (this.pushError) throw this.pushError
    const once = this.pushErrors.shift()
    if (once) throw once
    if (changes.length > 1 && changes.some((c) => this.refuseWhole.has(rowKey(c.table, c.id)))) {
      throw new SyncError('refused', 'invalid input syntax')
    }
    const result: PushResult = { applied: [], rejected: [] }
    for (const c of changes) {
      const key = rowKey(c.table, c.id)
      const reason = this.setAside.get(key) ?? (this.refuseWhole.has(key) ? 'invalid input syntax' : undefined)
      if (reason !== undefined) {
        result.rejected.push({ id: key, reason })
        continue
      }
      this.write(c.table, c.id, c.fields, c.times)
      result.applied.push(key)
    }
    return result
  }

  onPing(_owner: string, cb: () => void): () => void {
    this.pingers.push(cb)
    return () => {
      this.pingers = this.pingers.filter((p) => p !== cb)
    }
  }

  ping() {
    this.pingers.forEach((p) => p())
  }
}

const BOOK_FIELDS = [
  'isbn', 'title', 'authors', 'publisher', 'published_year', 'page_count', 'cover_url', 'description', 'status',
  'rating', 'notes', 'date_added', 'date_read', 'date_started', 'current_page', 'progress_percent', 'review', 'tags',
  'series', 'series_number', 'lent_to', 'lent_on', 'deleted',
]

/** A book as the server holds it: every column, each timed [at]. */
function serverBook(server: FakeServer, id: string, over: Record<string, unknown> = {}, at = 50) {
  const fields: Record<string, unknown> = {
    isbn: null, title: `Book ${id}`, authors: 'Someone', publisher: null, published_year: null, page_count: null,
    cover_url: null, description: null, status: 'TO_READ', rating: null, notes: '', date_added: 1, date_read: null,
    date_started: null, current_page: null, progress_percent: null, review: '', tags: '', series: null,
    series_number: null, lent_to: null, lent_on: null, deleted: false, ...over,
  }
  server.write('books', id, fields, Object.fromEntries(BOOK_FIELDS.map((f) => [f, at])))
}

function newBook(id: string, over: Partial<Book> = {}): Book {
  return {
    id, isbn: null, title: `New ${id}`, authors: 'Author', publisher: null, publishedYear: null, pageCount: null,
    coverUrl: null, description: null, status: 'TO_READ', rating: null, notes: '', dateAdded: 7, dateRead: null,
    dateStarted: null, currentPage: null, progressPercent: null, review: '', tags: [], series: null,
    seriesNumber: null, lentTo: null, lentOn: null, deleted: false, ...over,
  }
}

const OWNER = 'user-1'
let clock = 1_000
const stores: LibraryStore[] = []

function open(server: FakeServer, owner = OWNER, extra: Partial<ConstructorParameters<typeof LibraryStore>[0]> = {}) {
  const store = new LibraryStore({
    api: server, owner, now: () => clock, retryDelays: [60_000], pollMs: 0, ...extra,
  })
  stores.push(store)
  return store
}

function deferred() {
  let resolve!: () => void
  const promise = new Promise<void>((r) => (resolve = r))
  return { promise, resolve }
}

beforeEach(() => {
  // A fresh, empty browser database for each test.
  globalThis.indexedDB = new IDBFactory()
  clock = 1_000
})

afterEach(async () => {
  await Promise.all(stores.splice(0).map((s) => s.close()))
})

describe('LibraryStore', () => {
  it('loadsEverythingPageByPage', async () => {
    const server = new FakeServer()
    for (let i = 0; i < 1_200; i++) serverBook(server, `b${i}`)

    const store = open(server)
    // Asked twice (two views mounting at once), it opens and pulls once.
    await Promise.all([store.load(), store.load()])

    expect(store.books()).toHaveLength(1_200)
    expect(server.pulls).toEqual([[0, 500], [500, 500], [1_000, 500]])
    expect(store.book('b7')?.title).toBe('Book b7')

    // The next visit opens from the cache and pulls only what's new.
    await store.close()
    serverBook(server, 'late')
    server.pulls = []
    const again = open(server)
    await again.load()
    expect(server.pulls).toEqual([[1_200, 500]])
    expect(again.books()).toHaveLength(1_201)
  }, T)

  it('editAppliesAtOnceAndPushesStampedFields', async () => {
    const server = new FakeServer()
    serverBook(server, 'b1', { rating: 2, tags: 'a\n\nb' })
    const store = open(server)
    await store.load()
    const changes: [string, Book | null, Book | null][] = []
    store.onLocalChange((table, before, after) => changes.push([table, before as Book | null, after as Book | null]))

    clock = 2_000
    // The tags are the same list ("a\n\nb" reads as a, b): only the rating changed.
    store.edit('books', 'b1', { rating: 4, tags: ['a', 'b'] })

    expect(store.book('b1')?.rating).toBe(4)
    expect(store.status).toBe('saving')
    expect(changes).toHaveLength(1)
    expect(changes[0][0]).toBe('books')
    expect(changes[0][1]?.rating).toBe(2)
    expect(changes[0][2]?.rating).toBe(4)

    await store.idle()
    expect(server.pushes).toHaveLength(1)
    const [sent] = server.pushes[0]
    expect(sent.id).toBe('b1')
    expect(sent.fields.rating).toBe(4)
    expect(sent.times.rating).toBe(2_000)
    // Unchanged fields keep their own times (and the tags their text).
    expect(sent.times.tags).toBe(50)
    expect(sent.fields.tags).toBe('a\n\nb')
    expect(sent.times.title).toBe(50)
    expect(store.status).toBe('saved')
    expect(server.rows.get('books:b1')?.fields.rating).toBe(4)

    // An edit that changes nothing is not a change.
    clock = 3_000
    store.edit('books', 'b1', { rating: 4, tags: 'a\nb' })
    await store.idle()
    expect(server.pushes).toHaveLength(1)
    expect(changes).toHaveLength(1)
  }, T)

  it('concurrentEditsKeepTheNewestField', async () => {
    const server = new FakeServer()
    serverBook(server, 'b1', { rating: 1, notes: 'old' }, 50)
    const store = open(server)
    await store.load()

    // A phone changes the rating (at 90) and the notes (at 120) while the site pulls...
    server.write('books', 'b1', { rating: 3, notes: 'from the phone' }, { rating: 90, notes: 120 })
    const gate = deferred()
    server.holdPull = gate.promise
    const pulling = store.refresh()
    await new Promise((r) => setTimeout(r, 10))

    // ...and the site changes the rating at 100, before that pull lands.
    clock = 100
    store.edit('books', 'b1', { rating: 5 })
    gate.resolve()
    await pulling
    await store.idle()

    const book = store.book('b1')
    expect(book?.rating).toBe(5)
    expect(book?.notes).toBe('from the phone')
    // The queue still sent the site's rating, with its time.
    const last = server.pushes[server.pushes.length - 1].find((c) => c.id === 'b1')
    expect(last?.fields.rating).toBe(5)
    expect(last?.times.rating).toBe(100)
    expect(server.rows.get('books:b1')?.fields.rating).toBe(5)
    expect(server.rows.get('books:b1')?.fields.notes).toBe('from the phone')
  }, T)

  it('queuedChangesSurviveAReload', async () => {
    const server = new FakeServer()
    serverBook(server, 'b1', { rating: 1 })
    const first = open(server)
    await first.load()

    server.pushError = new SyncError('offline', 'Failed to fetch')
    clock = 2_000
    first.edit('books', 'b1', { rating: 5 })
    await first.idle()
    expect(first.status).toEqual({ offline: 1 })
    expect(server.rows.get('books:b1')?.fields.rating).toBe(1)
    await first.close()

    // The tab is closed; the next visit sends what was waiting.
    server.pushError = null
    server.pushes = []
    const second = open(server)
    await second.load()
    await second.idle()
    expect(server.pushes).toHaveLength(1)
    expect(server.pushes[0][0].fields.rating).toBe(5)
    expect(server.pushes[0][0].times.rating).toBe(2_000)
    expect(server.rows.get('books:b1')?.fields.rating).toBe(5)
    expect(second.book('b1')?.rating).toBe(5)
    expect(second.status).toBe('saved')

    // Sent and confirmed: the visit after that has nothing waiting.
    await second.close()
    const third = open(server)
    await third.load()
    await third.idle()
    expect(server.pushes).toHaveLength(1)
  }, T)

  it('rejectedChangesAreShownAndDropped', async () => {
    const server = new FakeServer()
    serverBook(server, 'b1')
    serverBook(server, 'b2')
    const store = open(server)
    await store.load()

    server.setAside.set('books:b1', 'value too long for type character varying(300)')
    store.edit('books', 'b1', { title: 'x'.repeat(400) })
    store.edit('books', 'b2', { rating: 3 })
    await store.idle()

    expect(store.status).toEqual({ rejected: 'value too long for type character varying(300)' })
    // The site keeps its value (as the phone does); the change leaves the queue.
    expect(store.book('b1')?.title).toBe('x'.repeat(400))
    expect(server.rows.get('books:b2')?.fields.rating).toBe(3)
    const pushed = server.pushes.length
    await store.refresh()
    expect(server.pushes).toHaveLength(pushed)

    // The next change clears the notice.
    store.edit('books', 'b2', { rating: 4 })
    expect(store.status).toBe('saving')
    await store.idle()
    expect(store.status).toBe('saved')
  }, T)

  it('aBatchRefusedAsAWholeIsSentOneByOne', async () => {
    const server = new FakeServer()
    serverBook(server, 'b1')
    serverBook(server, 'b2')
    const store = open(server)
    await store.load()

    server.refuseWhole.add('books:b1')
    store.edit('books', 'b1', { rating: 1 })
    store.edit('books', 'b2', { rating: 2 })
    await store.idle()

    expect(server.rows.get('books:b2')?.fields.rating).toBe(2)
    expect(store.status).toEqual({ rejected: 'invalid input syntax' })
    const pushed = server.pushes.length
    await store.refresh()
    expect(server.pushes).toHaveLength(pushed)
  }, T)

  it('aPingPulls', async () => {
    const server = new FakeServer()
    const store = open(server)
    await store.load()
    expect(store.books()).toHaveLength(0)
    let notified = 0
    store.subscribe(() => notified++)

    serverBook(server, 'phone', { title: 'Added on the phone' })
    server.ping()
    await store.idle()

    expect(store.book('phone')?.title).toBe('Added on the phone')
    expect(notified).toBeGreaterThan(0)
  }, T)

  it('aPullNeverFiresOnLocalChange', async () => {
    const server = new FakeServer()
    serverBook(server, 'b1')
    const store = open(server)
    const fired: string[] = []
    store.onLocalChange((...change) => {
      fired.push(change[0])
    })
    await store.load()

    server.write('books', 'b1', { rating: 5 }, { rating: 500 })
    serverBook(server, 'b2')
    server.write('quotes', 'q1', { book_id: 'b1', text: 'A line', deleted: false }, { book_id: 9, text: 9, deleted: 9 })
    server.write('challenges', '2026', { goal: 20, deleted: false }, { goal: 9, deleted: 9 })
    server.ping()
    await store.idle()
    server.write('books', 'b2', { deleted: true }, { deleted: 900 })
    await store.refresh()

    expect(store.book('b1')?.rating).toBe(5)
    expect(store.quotes('b1').map((q) => q.text)).toEqual(['A line'])
    expect(store.goal(2026)).toBe(20)
    expect(store.book('b2')).toBeUndefined()
    expect(fired).toEqual([])
  }, T)

  it('deletedBooksDisappear', async () => {
    const server = new FakeServer()
    serverBook(server, 'b1')
    serverBook(server, 'b2')
    serverBook(server, 'gone', { deleted: true })
    server.write('quotes', 'q1', { book_id: 'b2', text: 'Kept?', deleted: false }, { book_id: 60, text: 60, deleted: 60 })
    const store = open(server)
    await store.load()
    expect(store.books().map((b) => b.id).sort()).toEqual(['b1', 'b2'])

    // Deleted on a phone.
    server.write('books', 'b1', { deleted: true }, { deleted: 700 })
    server.ping()
    await store.idle()
    expect(store.book('b1')).toBeUndefined()
    expect(store.books().map((b) => b.id)).toEqual(['b2'])

    // Deleted here: gone at once, with its quotes, and the deletion is pushed.
    const changes: [string, unknown, unknown][] = []
    store.onLocalChange((table, before, after) => changes.push([table, before, after]))
    clock = 800
    store.remove('books', 'b2')
    expect(store.books()).toEqual([])
    expect(store.quotes('b2')).toEqual([])
    expect(changes.find((c) => c[0] === 'books')?.[2]).toBeNull()
    await store.idle()
    expect(server.rows.get('books:b2')?.fields.deleted).toBe(true)
    expect(server.rows.get('books:b2')?.times.deleted).toBe(800)
    expect(server.rows.get('quotes:q1')?.fields.deleted).toBe(true)

    // And they stay gone in the cache.
    await store.close()
    const again = open(server)
    await again.load()
    expect(again.books()).toEqual([])
  }, T)

  it('createAddsABookAndQueuesEveryField', async () => {
    const server = new FakeServer()
    const store = open(server)
    await store.load()
    const fired: [string, unknown, unknown][] = []
    store.onLocalChange((table, before, after) => fired.push([table, before, after]))

    clock = 3_000
    store.create(newBook('n1', { title: 'Fresh', tags: ['x', 'y'] }))
    expect(store.book('n1')?.title).toBe('Fresh')
    expect(fired).toHaveLength(1)
    expect(fired[0][1]).toBeNull()
    await store.idle()

    const sent = server.pushes[0][0]
    expect(sent.fields.title).toBe('Fresh')
    expect(sent.fields.tags).toBe('x\ny')
    expect(sent.times.title).toBe(3_000)
    expect(sent.times.deleted).toBe(3_000)
    expect(server.rows.get('books:n1')?.fields.status).toBe('TO_READ')

    // Editing a book that isn't here (removed for good meanwhile) never makes a half book.
    store.edit('books', 'nobody', { rating: 3 })
    expect(store.book('nobody')).toBeUndefined()
  }, T)

  it('restampingKeepsTheValueButSendsItAgain', async () => {
    const server = new FakeServer()
    serverBook(server, 'b1', { cover_url: 'photo' })
    const store = open(server)
    await store.load()

    clock = 4_000
    store.edit('books', 'b1', { cover_url: 'photo' }, { restamp: ['cover_url'] })
    await store.idle()
    expect(server.pushes[0][0].times.cover_url).toBe(4_000)
    expect(server.rows.get('books:b1')?.times.cover_url).toBe(4_000)
  }, T)

  it('goalsAndQuotes', async () => {
    const server = new FakeServer()
    serverBook(server, 'b1')
    server.write('challenges', 'not-a-year', { goal: 99, deleted: false }, { goal: 9, deleted: 9 })
    server.write('challenges', '2025', { goal: 12, deleted: false }, { goal: 9, deleted: 9 })
    const store = open(server)
    await store.load()

    expect(store.goal(2025)).toBe(12)
    expect(store.goal(2026)).toBeNull()
    expect(store.goal(Number.NaN)).toBeNull()

    // The year's row is made when its goal is first set.
    clock = 5_000
    store.edit('challenges', '2026', { goal: 25 })
    expect(store.goal(2026)).toBe(25)
    // Removed, then set again: the year's row comes back.
    store.remove('challenges', '2026')
    expect(store.goal(2026)).toBeNull()
    store.edit('challenges', '2026', { goal: 25 })
    expect(store.goal(2026)).toBe(25)

    store.createQuote({ id: 'q1', bookId: 'b1', text: 'Line', page: 3, dateAdded: 1, position: null, deleted: false })
    store.createQuote({ id: 'q2', bookId: 'b1', text: 'Other', page: null, dateAdded: 2, position: null, deleted: false })
    store.edit('quotes', 'q1', { text: 'Line, fixed' })
    store.remove('quotes', 'q2')
    expect(store.quotes('b1').map((q) => q.text)).toEqual(['Line, fixed'])
    await store.idle()

    expect(server.rows.get('challenges:2026')?.fields.goal).toBe(25)
    expect(server.rows.get('quotes:q1')?.fields.text).toBe('Line, fixed')
    expect(server.rows.get('quotes:q2')?.fields.deleted).toBe(true)
  }, T)

  it('retriesAfterTheDelaysAndAtOnceWhenOnline', async () => {
    const server = new FakeServer()
    serverBook(server, 'b1')
    const store = open(server, OWNER, { retryDelays: [40, 60_000] })
    await store.load()

    server.pushError = new SyncError('offline', 'Failed to fetch')
    store.edit('books', 'b1', { rating: 2 })
    await store.idle()
    expect(server.pushes).toHaveLength(1)
    expect(store.status).toEqual({ offline: 1 })

    // The first retry comes after 40 ms; the next would wait a minute.
    await new Promise((r) => setTimeout(r, 120))
    await store.idle()
    expect(server.pushes).toHaveLength(2)

    // Waiting out a failure, a new edit waits too; the status counts it.
    store.edit('books', 'b1', { title: 'Renamed' })
    await new Promise((r) => setTimeout(r, 20))
    expect(server.pushes).toHaveLength(2)

    // The connection is back: it retries at once.
    server.pushError = null
    window.dispatchEvent(new Event('online'))
    await new Promise((r) => setTimeout(r, 0))
    await store.idle()
    expect(server.pushes).toHaveLength(3)
    expect(server.rows.get('books:b1')?.fields.title).toBe('Renamed')
    expect(store.status).toBe('saved')
  }, T)

  it('focusPullsAndThePollPullsWhileVisible', async () => {
    const server = new FakeServer()
    const store = open(server, OWNER, { pollMs: 30 })
    await store.load()
    const afterLoad = server.pulls.length

    await new Promise((r) => setTimeout(r, 100))
    await store.idle()
    expect(server.pulls.length).toBeGreaterThan(afterLoad)

    await store.close()
    const quiet = open(server)
    await quiet.load()
    const before = server.pulls.length
    window.dispatchEvent(new Event('focus'))
    await new Promise((r) => setTimeout(r, 0))
    await quiet.idle()
    expect(server.pulls.length).toBe(before + 1)
  }, T)

  it('eachAccountHasItsOwnCache', async () => {
    const server = new FakeServer()
    serverBook(server, 'mine')
    const a = open(server, 'user-a')
    await a.load()
    await a.close()

    const other = new FakeServer()
    const b = open(other, 'user-b')
    await b.load()
    expect(b.books()).toEqual([])
  }, T)

  it('readsAreStableBetweenChanges', async () => {
    const server = new FakeServer()
    serverBook(server, 'b1')
    serverBook(server, 'b2')
    const store = open(server)
    await store.load()

    const books = store.books()
    const status: SaveStatus = store.status
    expect(store.books()).toBe(books)
    expect(store.status).toBe(status)
    expect(store.book('b1')).toBe(store.book('b1'))

    const b2 = store.book('b2')
    store.edit('books', 'b1', { rating: 1 })
    expect(store.books()).not.toBe(books)
    expect(store.book('b2')).toBe(b2)
    await store.idle()
  }, T)

  it('aStampAlwaysMovesForward', async () => {
    const server = new FakeServer()
    // Last written by a phone whose clock runs ahead of this computer's.
    serverBook(server, 'b1', { rating: 1 }, 2_000)
    const store = open(server)
    await store.load()

    clock = 1_000
    store.edit('books', 'b1', { rating: 4 })
    await store.idle()

    const sent = server.pushes[0][0]
    expect(sent.times.rating).toBeGreaterThan(2_000)
    expect(server.rows.get('books:b1')?.fields.rating).toBe(4)
    // Same millisecond, same field: the second value wins too.
    store.edit('books', 'b1', { rating: 5 })
    await store.idle()
    expect(server.rows.get('books:b1')?.fields.rating).toBe(5)
    await store.close()

    // The cache alone (nothing pulled) has the last value.
    const cacheOnly: SyncApi = {
      pull: async (after) => ({ rows: [], last: after }),
      push: async () => ({ applied: [], rejected: [] }),
      onPing: () => () => {},
    }
    const again = open(server, OWNER, { api: cacheOnly })
    await again.load()
    expect(again.book('b1')?.rating).toBe(5)
  }, T)

  it('aServerHiccupKeepsTheQueue', async () => {
    const server = new FakeServer()
    serverBook(server, 'b1')
    serverBook(server, 'b2')
    const store = open(server, OWNER, { retryDelays: [20] })
    await store.load()

    // A 404 while the API reloads its schema, then a 429 from the gateway: neither is about the data.
    server.pushErrors = [
      syncErrorOf({ message: 'Could not find the function public.sync_push', code: 'PGRST202' }, 404),
      syncErrorOf({ message: 'Too Many Requests' }, 429),
    ]
    store.edit('books', 'b1', { rating: 1 })
    store.edit('books', 'b2', { rating: 2 })
    await store.idle()
    expect(store.status).toEqual({ offline: 2 })

    await new Promise((r) => setTimeout(r, 150))
    await store.idle()
    expect(server.rows.get('books:b1')?.fields.rating).toBe(1)
    expect(server.rows.get('books:b2')?.fields.rating).toBe(2)
    expect(store.status).toBe('saved')
  }, T)

  it('aTooLargeBatchIsSplitAndNothingIsDropped', async () => {
    const server = new FakeServer()
    serverBook(server, 'b1')
    serverBook(server, 'b2')
    const store = open(server)
    await store.load()

    // The batch is too big; sent one at a time, both arrive.
    server.pushErrors = [syncErrorOf({ message: 'Payload Too Large' }, 413)]
    store.edit('books', 'b1', { rating: 1 })
    store.edit('books', 'b2', { rating: 2 })
    await store.idle()
    expect(server.rows.get('books:b1')?.fields.rating).toBe(1)
    expect(server.rows.get('books:b2')?.fields.rating).toBe(2)
    expect(store.status).toBe('saved')

    // One row alone too big: it waits for a retry, never set aside.
    server.pushError = syncErrorOf({ message: 'Payload Too Large' }, 413)
    store.edit('books', 'b1', { rating: 3 })
    await store.idle()
    expect(store.status).toEqual({ offline: 1 })
  }, T)

  it('anotherTabsNewerEditStaysQueued', async () => {
    const server = new FakeServer()
    serverBook(server, 'b1', { rating: 1, notes: 'old' })
    server.pushError = new SyncError('offline', 'Failed to fetch')

    // Tab B queues a change while offline.
    const b = open(server)
    await b.load()
    clock = 2_000
    b.edit('books', 'b1', { rating: 5 })
    await b.idle()

    // Tab A opens and sees B's waiting change; still offline.
    const a = open(server)
    await a.load()
    expect(a.book('b1')?.rating).toBe(5)

    // B changes the notes, then is closed before it can push.
    clock = 3_000
    b.edit('books', 'b1', { notes: 'newer' })
    await b.idle()
    await b.close()

    // A pushes its own (older) copy of the row: B's entry is not A's to remove.
    server.pushError = null
    await a.refresh()
    await a.idle()
    expect(server.rows.get('books:b1')?.fields.rating).toBe(5)
    expect(server.rows.get('books:b1')?.fields.notes).toBe('old')
    await a.close()

    // The next visit sends B's notes.
    const c = open(server)
    await c.load()
    await c.idle()
    expect(server.rows.get('books:b1')?.fields.notes).toBe('newer')
  }, T)

  it('aPushedRowSettlesWithoutRequeuing', async () => {
    const server = new FakeServer()
    serverBook(server, 'b1')
    const store = open(server)
    await store.load()

    clock = 2_000
    store.edit('books', 'b1', { rating: 3, tags: ['x'] })
    await store.idle()
    expect(server.pushes).toHaveLength(1)

    // The push came back in the pull (its seq moved): nothing is queued again.
    await store.refresh()
    await store.refresh()
    expect(server.pushes).toHaveLength(1)
    expect(store.status).toBe('saved')
    expect(store.book('b1')?.rating).toBe(3)
  }, T)

  it('aRemoteEditLeavesADeletedBookDeleted', async () => {
    const server = new FakeServer()
    serverBook(server, 'b1')
    const store = open(server)
    await store.load()

    // Deleted here while a pull is under way; meanwhile a phone renames it.
    const gate = deferred()
    server.holdPull = gate.promise
    const pulling = store.refresh()
    await new Promise((r) => setTimeout(r, 10))
    clock = 800
    store.remove('books', 'b1')
    server.write('books', 'b1', { title: 'Renamed on the phone' }, { title: 900 })
    gate.resolve()
    await pulling
    expect(store.book('b1')).toBeUndefined()
    expect(store.books()).toEqual([])

    await store.idle()
    expect(server.rows.get('books:b1')?.fields.deleted).toBe(true)
    expect(server.rows.get('books:b1')?.fields.title).toBe('Renamed on the phone')
    await store.refresh()
    expect(store.book('b1')).toBeUndefined()

    // Renamed again later on the phone: still deleted.
    server.write('books', 'b1', { title: 'Again' }, { title: 5_000 })
    await store.refresh()
    expect(store.books()).toEqual([])
  }, T)
})
