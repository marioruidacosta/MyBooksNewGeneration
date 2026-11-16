import { PULL_MAX, PUSH_BATCH, rowKey, SyncError, type PushResult, type SyncApi, type SyncErrorKind } from './api'
import {
  bookToFields, quoteToFields, rowToBook, rowToChallenge, rowToQuote,
} from './codec'
import { mergeFields } from './merge'
import { TAG_SEPARATOR, type Book, type Challenge, type Quote, type SyncRow, type SyncTable } from './model'
import {
  BLOCKED_MESSAGE, LEASE_HEARTBEAT_MS, LocalDb, sameRow, SUPERSEDED_MESSAGE, type Cached, sameValue, type QueueEntry,
  type StoredPublish,
} from './queue'

/**
 * What the save badge shows. `offline`: no answer, with the changes waiting. `signedOut`: the server doesn't know the
 * session any more (sign in again; the changes wait). `rejected`: how many changes the server refused (the site keeps
 * its values); the person dismisses the notice.
 */
export type SaveStatus = 'saved' | 'saving' | { offline: number } | { signedOut: true } | { rejected: number }

/** The tables the site edits, and what each row reads as. */
export type EditableTable = 'books' | 'quotes' | 'challenges'
type Entity = { books: Book; quotes: Quote; challenges: Challenge }

/**
 * A change made on the site (never a pulled one): the row before and after it, null where it doesn't exist or is
 * deleted (so `before` is null for a create, `after` null for a removal).
 */
export type LocalChangeArgs = {
  [T in EditableTable]: [table: T, before: Entity[T] | null, after: Entity[T] | null]
}[EditableTable]

/**
 * Where a local change comes from, when it isn't an ordinary edit: `import` is a bulk write (a backup or Goodreads
 * import, filling in details) that, as on the phone, is never posted to the community.
 */
export type ChangeOrigin = 'import'

/** What [LibraryStore.onLocalChangeWithOrigin] calls: the change, then its [ChangeOrigin] (undefined for an ordinary edit). */
export type LocalChangeWithOrigin = (...args: [...LocalChangeArgs, origin: ChangeOrigin | undefined]) => void

type WriteOptions = { restamp?: string[]; origin?: ChangeOrigin }

export type StoreOptions = {
  api: SyncApi
  /** The signed-in account's id: the cache is this account's own database. */
  owner: string
  now?: () => number
  /** Waits after a failed pass, the last one repeating. */
  retryDelays?: number[]
  /** Pull this often while the page is visible; 0 never. */
  pollMs?: number
  /** Waits before trying once more when the browser's copy of the library won't open. */
  openRetryMs?: number
}

const RETRY_DELAYS = [5_000, 15_000, 60_000, 300_000]
const POLL_MS = 60_000
const OPEN_RETRY_MS = 500

const isDeleted = (row: SyncRow): boolean => row.fields.deleted === true

const isOffline = (s: SaveStatus): s is { offline: number } => typeof s === 'object' && 'offline' in s
const isSignedOut = (s: SaveStatus): boolean => typeof s === 'object' && 'signedOut' in s
const isRejected = (s: SaveStatus): boolean => typeof s === 'object' && 'rejected' in s

const splitTags = (v: unknown): string[] =>
  (typeof v === 'string' ? v.split(TAG_SEPARATOR) : Array.isArray(v) ? v.map(String) : []).filter((t) => t.length > 0)

/** The field's value as it travels: tags as one text. */
function encode(field: string, table: SyncTable, value: unknown): unknown {
  if (table === 'books' && field === 'tags' && Array.isArray(value)) return value.map(String).join(TAG_SEPARATOR)
  return value
}

/** Whether [next] changes the field's [current] value. Tags compare as lists: "a\n\nb" is the list a, b. */
function changes(field: string, table: SyncTable, current: unknown, next: unknown): boolean {
  if (table === 'books' && field === 'tags') {
    return !sameValue(splitTags(current), splitTags(next))
  }
  return !sameValue(current, next)
}

/**
 * The person's cloud library, in the browser. It opens from the browser's cache (one database per account), then
 * keeps in step with the cloud as the app does (`LibrarySync.kt`):
 * - An edit applies at once: only the fields whose value changed are stamped with the time, the others keep theirs;
 *   the row is queued (the queue is kept in the cache, so it survives a reload) and pushed.
 * - A pass pushes the queue (each queued row as its current fields and times, in batches), then pulls every page
 *   after the stored cursor. Pulled rows merge field by field, newest wins, so a queued local value newer than the
 *   pulled one stays (and is still sent). A row the server holds as deleted is removed for good here.
 * - A pass runs at load, after an edit, on a ping from a phone, on focus, and every minute while the page is visible.
 *   After a failure it waits 5 s, 15 s, 60 s, then 300 s (repeating); `online` and focus cut the wait short.
 * - A change the server refuses leaves the queue (the site keeps its value, as the phone does) and is shown.
 */
export class LibraryStore {
  private readonly api: SyncApi
  private readonly owner: string
  private readonly now: () => number
  private readonly retryDelays: number[]
  private readonly pollMs: number
  private readonly openRetryMs: number
  /** Names this store's queue entries apart from other tabs'. */
  private readonly writer = Math.random().toString(36).slice(2)

  private db: LocalDb | null = null
  private loading: Promise<void> | null = null
  private loaded = false
  private closed = false
  private rows = new Map<string, SyncRow>()
  private queue = new Map<string, { entry: QueueEntry; keys: string[] }>()
  private seq = 0
  /** Whether a pull has worked since this store opened. */
  private pulled = false
  /** What stopped the last pass (null: it worked). */
  private lastError: SyncErrorKind | null = null
  /** The browser's copy of the library wouldn't open: everything works from the cloud, nothing is kept here. */
  private _localCopyFailed = false
  /** Something the person must know about the browser's copy (another tab in the way, or a newer version); null: nothing. */
  private _notice: string | null = null
  private heartbeat: ReturnType<typeof setInterval> | null = null

  private _status: SaveStatus = 'saved'
  /** How many changes the server refused that the person hasn't dismissed (kept with the queue). */
  private refused = 0

  private listeners = new Set<() => void>()
  private localListeners = new Set<(...args: LocalChangeArgs) => void>()
  private originListeners = new Set<LocalChangeWithOrigin>()

  /** Writes to the cache, in the order the memory changed. */
  private writes: Promise<void> = Promise.resolve()
  /** Cache writes started and not yet finished. */
  private pendingWrites = 0

  // The worker: one pass at a time.
  private running: Promise<void> | null = null
  private wanted = false
  private urgent = false
  private failures = 0
  private retryTimer: ReturnType<typeof setTimeout> | null = null
  private pollTimer: ReturnType<typeof setInterval> | null = null
  private passWaiters: (() => void)[] = []
  private stopPings: (() => void) | null = null

  // Memoized reads, so a view sees the same objects until something changes.
  private bookCache = new WeakMap<SyncRow, Book>()
  private quoteCache = new WeakMap<SyncRow, Quote>()
  private booksMemo: Book[] | null = null
  private quotesMemo = new Map<string, Quote[]>()

  constructor(options: StoreOptions) {
    this.api = options.api
    this.owner = options.owner
    this.now = options.now ?? Date.now
    this.retryDelays = options.retryDelays?.length ? options.retryDelays : RETRY_DELAYS
    this.pollMs = options.pollMs ?? POLL_MS
    this.openRetryMs = options.openRetryMs ?? OPEN_RETRY_MS
  }

  // ---- lifecycle ----

  /** Opens the cache, then pushes what was waiting and pulls from the stored cursor; resolves after that first pass. */
  load(): Promise<void> {
    this.loading ??= this.open()
    return this.loading
  }

  private async open(): Promise<void> {
    const opened = await this.openLocalCopy()
    if (this.closed) {
      opened?.db.close()
      return
    }
    if (opened) {
      this.db = opened.db
      this.startLease(opened.db)
      this.rows = opened.cached.rows
      this.queue = opened.cached.queue
      this.seq = opened.cached.seq
      this.refused = opened.cached.refused
      if (this.refused > 0) this._status = { rejected: this.refused }
    } else {
      this._localCopyFailed = true
    }
    this.loaded = true
    this.dataChanged()
    this.listen()
    await this.refresh()
  }

  /** Opens and reads the browser's copy of the library; once more after a short wait when that fails; null when it can't. */
  private async openLocalCopy(): Promise<{ db: LocalDb; cached: Cached } | null> {
    for (let attempt = 0; attempt < 2; attempt++) {
      if (attempt > 0) await new Promise((r) => setTimeout(r, this.openRetryMs))
      if (this.closed) return null
      let db: LocalDb | null = null
      try {
        db = await LocalDb.open(this.owner, {
          onBlocked: () => this.setNotice(BLOCKED_MESSAGE),
          onBlocking: () => {
            // The connection is closed already: nothing more is kept here, and the person must reload.
            this.db = null
            this.stopLease()
            this.setNotice(SUPERSEDED_MESSAGE)
          },
        })
        if (this._notice === BLOCKED_MESSAGE) this.setNotice(null)
        return { db, cached: await db.read() }
      } catch (e) {
        console.error('LibraryStore: could not open the browser copy of the library', e)
        try {
          db?.close()
        } catch {
          // Nothing more to do with it.
        }
      }
    }
    return null
  }

  /** Stops syncing; the cache, queue included, stays for the next visit (or sign-in). */
  async close(): Promise<void> {
    if (this.closed) return
    this.closed = true
    if (this.retryTimer) clearTimeout(this.retryTimer)
    if (this.pollTimer) clearInterval(this.pollTimer)
    this.retryTimer = this.pollTimer = null
    this.stopPings?.()
    this.stopPings = null
    this.stopLease()
    if (typeof window !== 'undefined') {
      window.removeEventListener('online', this.onOnline)
      window.removeEventListener('focus', this.onFocus)
    }
    // A pass still waiting on the network stops at its next step; nothing it gets is applied or cached.
    await this.writes
    this.passWaiters.splice(0).forEach((w) => w())
    // Said goodbye: another tab may take up the community posts left waiting.
    await this.db?.write({ lease: { writer: this.writer, at: null } }).catch(() => undefined)
    this.db?.close()
    this.db = null
  }

  /** Says this tab is alive now and every [LEASE_HEARTBEAT_MS], so another tab leaves its community posts alone. */
  private startLease(db: LocalDb) {
    const beat = () => {
      void db.write({ lease: { writer: this.writer, at: Date.now() } }).catch(() => undefined)
    }
    beat()
    this.heartbeat = setInterval(beat, LEASE_HEARTBEAT_MS)
  }

  private stopLease() {
    if (this.heartbeat) clearInterval(this.heartbeat)
    this.heartbeat = null
  }

  /** What the person must know about the browser's copy (see the messages in `queue.ts`); null when nothing. */
  get notice(): string | null {
    return this._notice
  }

  private setNotice(next: string | null) {
    if (this._notice === next) return
    this._notice = next
    this.emit()
  }

  /** Pushes and pulls now (cutting a retry wait short); resolves when that pass is over. */
  refresh(): Promise<void> {
    if (this.closed || !this.loaded) return Promise.resolve()
    const done = new Promise<void>((resolve) => this.passWaiters.push(resolve))
    this.request(true)
    return done
  }

  /** Resolves once no pass is running or due (a retry wait aside) and the cache has every change. */
  async idle(): Promise<void> {
    for (;;) {
      await Promise.resolve()
      if (this.running) {
        await this.running
        continue
      }
      if (!this.closed && this.retryTimer === null && (this.wanted || this.urgent)) {
        await new Promise((r) => setTimeout(r, 0))
        continue
      }
      break
    }
    await this.writes
  }

  // ---- reading ----

  get status(): SaveStatus {
    return this._status
  }

  /** The browser's copy of the library wouldn't open (twice): nothing is kept here, so changes can't be saved here. */
  get localCopyFailed(): boolean {
    return this._localCopyFailed
  }

  /**
   * A first visit with no answer: nothing is kept in this browser and the cloud hasn't answered yet, so an empty library
   * means "not here yet", not "no books".
   */
  get firstVisitOffline(): boolean {
    return this.loaded && this.seq === 0 && !this.pulled && this.lastError === 'offline'
  }

  /** The person has read the notice about refused changes. */
  dismissRefused(): void {
    if (this.refused === 0) return
    this.refused = 0
    this.persist({ refusedClear: true })
    if (isRejected(this._status)) this.setStatus(this.queue.size > 0 ? 'saving' : 'saved')
  }

  /**
   * Whether closing the tab now could lose a change: a cache write is unfinished, or a change is being sent. (Changes
   * waiting offline are in the cache, so they survive and go at the next visit.)
   */
  hasPendingWrites(): boolean {
    return this.pendingWrites > 0 || this._status === 'saving'
  }

  /** Called after any change: data or status. Returns the way to stop. */
  subscribe = (cb: () => void): (() => void) => {
    this.listeners.add(cb)
    return () => {
      this.listeners.delete(cb)
    }
  }

  /** Called for each change made on the site (never for a pulled one). Returns the way to stop. */
  onLocalChange(cb: (...args: LocalChangeArgs) => void): () => void {
    this.localListeners.add(cb)
    return () => {
      this.localListeners.delete(cb)
    }
  }

  /** Like [onLocalChange], with where the change came from (`import` for a bulk write that isn't posted to the community). */
  onLocalChangeWithOrigin(cb: LocalChangeWithOrigin): () => void {
    this.originListeners.add(cb)
    return () => {
      this.originListeners.delete(cb)
    }
  }

  /** The live books. */
  books(): Book[] {
    if (!this.booksMemo) {
      const out: Book[] = []
      for (const row of this.rows.values()) {
        if (row.table === 'books' && !isDeleted(row)) out.push(this.asBook(row))
      }
      this.booksMemo = out
    }
    return this.booksMemo
  }

  /** A live book. */
  book(id: string): Book | undefined {
    const row = this.rows.get(rowKey('books', id))
    return row && !isDeleted(row) ? this.asBook(row) : undefined
  }

  /** When each field of a live book last changed (ms, by the server's field name); empty for a book that isn't here. */
  bookTimes(id: string): Record<string, number> {
    const row = this.rows.get(rowKey('books', id))
    return row && !isDeleted(row) ? { ...row.times } : {}
  }

  /** A book's live quotes, oldest first. */
  quotes(bookId: string): Quote[] {
    let out = this.quotesMemo.get(bookId)
    if (!out) {
      out = []
      for (const row of this.rows.values()) {
        if (row.table !== 'quotes' || isDeleted(row) || row.fields.book_id !== bookId) continue
        out.push(this.asQuote(row))
      }
      out.sort((a, b) => a.dateAdded - b.dateAdded || (a.id < b.id ? -1 : a.id > b.id ? 1 : 0))
      this.quotesMemo.set(bookId, out)
    }
    return out
  }

  /** The ebook a phone keeps for a book (the site only shows it), or undefined. A new object each call. */
  ebook(bookId: string): { format: string | null; fileName: string | null; size: number | null } | undefined {
    const row = this.rows.get(rowKey('ebooks', bookId))
    if (!row || isDeleted(row)) return undefined
    const text = (v: unknown) => (typeof v === 'string' ? v : null)
    return {
      format: text(row.fields.format),
      fileName: text(row.fields.file_name),
      size: typeof row.fields.size === 'number' ? row.fields.size : null,
    }
  }

  /** Whether a live book has bookmarks or an e-book record from a phone (removing the book removes them). */
  hasPhoneData(bookId: string): boolean {
    for (const row of this.rows.values()) {
      if (isDeleted(row)) continue
      if ((row.table === 'bookmarks' && row.fields.book_id === bookId) || (row.table === 'ebooks' && row.id === bookId)) return true
    }
    return false
  }

  /** The reading goal for [year], or null. Rows whose id isn't a year are skipped. */
  goal(year: number): number | null {
    if (!Number.isInteger(year)) return null
    for (const row of this.rows.values()) {
      if (row.table !== 'challenges' || isDeleted(row)) continue
      const c = rowToChallenge(row)
      if (!Number.isNaN(c.year) && c.year === year) return c.goal
    }
    return null
  }

  /** Every live reading goal, by year (rows whose id isn't a year are skipped). */
  goals(): { year: number; goal: number }[] {
    const out: { year: number; goal: number }[] = []
    for (const row of this.rows.values()) {
      if (row.table !== 'challenges' || isDeleted(row)) continue
      const c = rowToChallenge(row)
      if (!Number.isNaN(c.year)) out.push({ year: c.year, goal: c.goal })
    }
    return out.sort((a, b) => a.year - b.year)
  }

  /**
   * The community posts left waiting by a tab that is gone (the publisher takes them up again), now this tab's. Those
   * of a tab still open stay with it; those of another account are forgotten.
   */
  async loadPublishes(owner: string): Promise<StoredPublish[]> {
    try {
      await this.writes
      return (await this.db?.adoptPublishes(this.writer, owner, Date.now())) ?? []
    } catch (e) {
      console.error('LibraryStore: could not read the waiting community posts', e)
      return []
    }
  }

  /** Keeps this community post for the next visit, replacing this tab's earlier one for the book. */
  putPublish(publish: StoredPublish): void {
    this.persist({ writer: this.writer, publishPut: [publish] })
  }

  /** This tab no longer keeps a community post for the book (it was sent or dropped). */
  deletePublish(id: string): void {
    this.persist({ writer: this.writer, publishDelete: [id] })
  }

  // ---- changing ----

  /**
   * Sets fields (by their server names; `tags` as a list or as one text) on a row. Only the fields whose value changes
   * are stamped; the rest keep their times. [restamp] fields are stamped even when their value is the same (a new
   * cover photo under the same `"photo"` marker). A book or quote that isn't here does nothing (use [create] or
   * [createQuote]); a challenge row is made when its year's goal is first set.
   */
  edit(table: EditableTable, id: string, fields: Record<string, unknown>, options: WriteOptions = {}): void {
    if (table === 'challenges') {
      // Setting a year's goal brings back its row if it was deleted, as the app's upsert does.
      this.write(table, id, { deleted: false, ...fields }, options.restamp ?? [], true, options.origin)
      return
    }
    this.write(table, id, fields, options.restamp ?? [], false, options.origin)
  }

  /** Adds a book (or, when its id is here already, sets every field of it). */
  create(book: Book, options: { origin?: ChangeOrigin } = {}): void {
    this.write('books', book.id, bookToFields(book), [], true, options.origin)
  }

  /** Adds a quote (or, when its id is here already, sets every field of it). */
  createQuote(quote: Quote): void {
    this.write('quotes', quote.id, quoteToFields(quote), [], true)
  }

  /** Deletes a row (marks it deleted, which syncs); a book takes its quotes, bookmarks and ebook record with it. */
  remove(table: EditableTable, id: string): void {
    if (table === 'books') {
      for (const row of [...this.rows.values()]) {
        if (isDeleted(row)) continue
        const child = ((row.table === 'quotes' || row.table === 'bookmarks') && row.fields.book_id === id) ||
          (row.table === 'ebooks' && row.id === id)
        if (child) this.write(row.table, row.id, { deleted: true }, [], false)
      }
    }
    this.write(table, id, { deleted: true }, [], false)
  }

  /**
   * Brings back a book this site removed, writing only `deleted: false`, so a field someone else changed since (a
   * phone, within the seconds Undo is on offer) keeps its newer value. When the row is gone for good (the server's
   * deleted copy came back and it was dropped) there is nothing to bring back, so [book] is created whole.
   */
  restoreBook(book: Book): void {
    if (this.rows.has(rowKey('books', book.id))) this.write('books', book.id, { deleted: false }, [], false)
    else this.create(book)
  }

  /** As [restoreBook], for a quote. */
  restoreQuote(quote: Quote): void {
    if (this.rows.has(rowKey('quotes', quote.id))) this.write('quotes', quote.id, { deleted: false }, [], false)
    else this.createQuote(quote)
  }

  private write(
    table: SyncTable, id: string, fields: Record<string, unknown>, restamp: string[], allowNew: boolean,
    origin?: ChangeOrigin,
  ) {
    if (!this.loaded || this.closed) throw new Error('LibraryStore: not loaded')
    const key = rowKey(table, id)
    const current = this.rows.get(key)
    if (!current && !allowNew) return

    const nextFields: Record<string, unknown> = { ...(current?.fields ?? {}) }
    const changed: string[] = []
    for (const [field, value] of Object.entries(fields)) {
      if (value === undefined) continue
      const isNew = !current || !(field in current.fields)
      if (isNew || changes(field, table, current.fields[field], value) || restamp.includes(field)) {
        changed.push(field)
        nextFields[field] = encode(field, table, value)
      }
    }
    if (!current && !('deleted' in nextFields)) {
      nextFields.deleted = false
      changed.push('deleted')
    }
    if (changed.length === 0) return

    // Each stamp moves its field's time forward even when the clock doesn't (the same millisecond, a clock set back,
    // or a time from a phone whose clock runs ahead): an edit no newer than the field would be ignored by the cache
    // and the server alike, and lost while showing "Saved". Unchanged fields keep their times.
    const now = this.now()
    const old = current?.times ?? {}
    const stamped = Object.fromEntries(changed.map((f) => [f, Math.max(now, (old[f] ?? 0) + 1)]))
    const next: SyncRow = { table, id, fields: nextFields, times: { ...old, ...stamped } }
    this.rows.set(key, next)

    // Queued once per row; the stored entry is this tab's own, so another tab never removes it.
    const entry: QueueEntry = { table, id }
    const mine = `${this.writer}|${key}`
    const held = this.queue.get(key)
    const enqueue = !held || !held.keys.includes(mine)
    if (!held) this.queue.set(key, { entry, keys: [mine] })
    else if (enqueue) held.keys.push(mine)
    this.persist({ put: [next], enqueue: enqueue ? [{ key: mine, entry }] : [] })

    // Waiting out a failure, the change waits too (and is counted); otherwise it is on its way.
    this._status = isOffline(this._status) ? { offline: this.queue.size } : isSignedOut(this._status) ? this._status : 'saving'
    this.dataChanged()
    this.fireLocal(table, current, next, origin)
    this.request(false)
  }

  private fireLocal(table: SyncTable, before: SyncRow | undefined, after: SyncRow, origin?: ChangeOrigin) {
    if (this.localListeners.size === 0 && this.originListeners.size === 0) return
    const live = (row: SyncRow | undefined) => (row && !isDeleted(row) ? row : null)
    const b = live(before)
    const a = live(after)
    if (!b && !a) return // a deleted row stays deleted
    let args: LocalChangeArgs
    if (table === 'books') args = ['books', b && this.asBook(b), a && this.asBook(a)]
    else if (table === 'quotes') args = ['quotes', b && this.asQuote(b), a && this.asQuote(a)]
    else if (table === 'challenges') {
      const bc = b && rowToChallenge(b)
      const ac = a && rowToChallenge(a)
      if (Number.isNaN(bc?.year ?? ac?.year)) return
      args = ['challenges', bc, ac]
    } else return
    for (const cb of [...this.localListeners]) {
      try {
        cb(...args)
      } catch (e) {
        console.error('LibraryStore: a local change listener failed', e)
      }
    }
    for (const cb of [...this.originListeners]) {
      try {
        cb(...args, origin)
      } catch (e) {
        console.error('LibraryStore: a local change listener failed', e)
      }
    }
  }

  // ---- the worker ----

  private listen() {
    this.stopPings = this.api.onPing(this.owner, () => this.request(false))
    if (typeof window !== 'undefined') {
      window.addEventListener('online', this.onOnline)
      window.addEventListener('focus', this.onFocus)
    }
    if (this.pollMs > 0) {
      this.pollTimer = setInterval(() => {
        if (typeof document === 'undefined' || document.visibilityState === 'visible') this.request(false)
      }, this.pollMs)
    }
  }

  private onOnline = () => this.request(true)
  private onFocus = () => this.request(true)

  /**
   * Asks for a pass. [urgent] (retry now: the connection is back, focus, a refresh) cuts a retry wait short; anything
   * else waiting out a failure waits with the rest (the retry covers it).
   */
  private request(urgent: boolean) {
    if (this.closed || !this.loaded) return
    if (urgent) {
      this.urgent = true
      if (this.retryTimer) {
        clearTimeout(this.retryTimer)
        this.retryTimer = null
      }
    } else {
      this.wanted = true
    }
    if (this.running || this.retryTimer) return
    // After the current task: edits made together go in one push.
    this.running = Promise.resolve().then(() => this.drive())
  }

  private async drive() {
    try {
      while (!this.closed && this.retryTimer === null && (this.wanted || this.urgent)) {
        // This pass covers everything asked for so far.
        this.wanted = this.urgent = false
        const waiting = this.passWaiters.splice(0)
        const error = await this.pass()
        waiting.forEach((w) => w())
        if (this.closed) return
        if (!error) {
          this.failures = 0
          continue
        }
        this.setStatus(this.lastError === 'auth' ? { signedOut: true } : { offline: this.queue.size })
        if (this.urgent) continue // a retry-now came in meanwhile
        const wait = this.retryDelays[Math.min(this.failures, this.retryDelays.length - 1)]
        this.failures++
        this.retryTimer = setTimeout(() => {
          this.retryTimer = null
          this.request(true)
        }, wait)
      }
    } finally {
      this.running = null
      if (this.closed) this.passWaiters.splice(0).forEach((w) => w())
    }
  }

  /** One push and pull; the error that stopped it, or null. */
  private async pass(): Promise<unknown> {
    // Changes waiting since the last visit are on their way; a retry keeps showing "Offline" until it succeeds.
    if (this.queue.size > 0 && this._status === 'saved') this.setStatus('saving')
    try {
      await this.pushQueue()
      if (this.closed) return null
      await this.pull()
      if (this.closed) return null
      await this.writes
      this.lastError = null
      this.setStatus(this.refused > 0 ? { rejected: this.refused } : this.queue.size > 0 ? 'saving' : 'saved')
      return null
    } catch (e) {
      if (!(e instanceof SyncError)) console.error('LibraryStore: sync failed', e)
      this.lastError = e instanceof SyncError ? e.kind : 'other'
      return e
    }
  }

  /**
   * Sends the queue: each entry as its row's current fields and times. An entry leaves the queue once the server has
   * applied or refused exactly what was sent; a row edited meanwhile stays queued (its new values go next), and an
   * entry whose row is gone (removed for good) is dropped.
   */
  private async pushQueue() {
    for (;;) {
      const batch = [...this.queue.entries()].slice(0, PUSH_BATCH)
      if (batch.length === 0) return
      const sent = new Map<string, SyncRow>()
      for (const [key] of batch) {
        const row = this.rows.get(key)
        if (row) sent.set(key, row)
      }
      const result = sent.size === 0 ? { applied: [], rejected: [] } : await this.pushOrSplit([...sent.values()])
      if (this.closed) return
      const done = new Set([...result.applied, ...result.rejected.map((r) => r.id)])
      const dequeue: string[] = []
      const dequeueIfStored: { table: SyncTable; id: string; keys: string[]; sent: SyncRow | null }[] = []
      const own = `${this.writer}|`
      const refusedKeys = new Set(result.rejected.map((r) => r.id))
      let refusedNow = 0
      for (const [key, held] of batch) {
        const current = this.rows.get(key)
        const sentRow = sent.get(key)
        const finished = sentRow ? done.has(key) && current !== undefined && sameRow(current, sentRow) : !current
        if (!finished || this.queue.get(key) !== held) continue
        this.queue.delete(key)
        if (refusedKeys.has(key)) refusedNow++
        dequeue.push(...held.keys.filter((k) => k.startsWith(own)))
        // Entries another tab queued (seen at load) may stand for a newer change of its own, cached but not sent.
        const others = held.keys.filter((k) => !k.startsWith(own))
        if (others.length > 0) {
          dequeueIfStored.push({ table: held.entry.table, id: held.entry.id, keys: others, sent: sentRow ?? null })
        }
      }
      // The server's own words are for the log; the person is told how many.
      for (const r of result.rejected) console.warn(`LibraryStore: the server refused ${r.id}: ${r.reason}`)
      if (refusedNow > 0) this.refused += refusedNow
      if (dequeue.length > 0 || dequeueIfStored.length > 0 || refusedNow > 0) {
        this.persist({ dequeue, dequeueIfStored, ...(refusedNow > 0 ? { refusedAdd: refusedNow } : {}) })
      }
      // Nothing could be confirmed (every row changed meanwhile): their own requests send them again.
      if (dequeue.length === 0 && dequeueIfStored.length === 0) return
    }
  }

  /**
   * Pushes [rows]. When the server can't read the request as a whole (a 400: a value it can't even parse) or finds it
   * too large (413), resends them one at a time. A row refused on its own (400) is set aside, so the rest goes
   * through; a row too large on its own waits, like any other failure. Any other failure is thrown: the rows aren't
   * to blame, and the batch is retried later.
   */
  private async pushOrSplit(rows: SyncRow[]): Promise<PushResult> {
    const changes = (rs: SyncRow[]) => rs.map((r) => ({ table: r.table, id: r.id, fields: r.fields, times: r.times }))
    try {
      return await this.api.push(changes(rows))
    } catch (e) {
      const splittable = e instanceof SyncError && (e.kind === 'refused' || e.kind === 'tooLarge')
      if (!splittable || rows.length === 1 && e.kind === 'tooLarge') throw e
    }
    const out: PushResult = { applied: [], rejected: [] }
    for (const row of rows) {
      try {
        const one = await this.api.push(changes([row]))
        out.applied.push(...one.applied)
        out.rejected.push(...one.rejected)
      } catch (e) {
        if (!(e instanceof SyncError && e.kind === 'refused')) throw e
        out.rejected.push({ id: rowKey(row.table, row.id), reason: e.message })
      }
    }
    return out
  }

  /** Pulls every row changed after the cursor, a page at a time; each page is cached with its cursor. */
  private async pull() {
    let after = this.seq
    for (;;) {
      const page = await this.api.pull(after, PULL_MAX)
      if (this.closed) return
      this.pulled = true
      this.apply(page.rows, page.last)
      if (page.rows.length < PULL_MAX || page.last <= after) return
      after = page.last
    }
  }

  /** Merges pulled rows, field by field (newest wins). A row the server holds as deleted is removed for good. */
  private apply(pulled: SyncRow[], last: number) {
    const put: SyncRow[] = []
    const drop: string[] = []
    const gone = new Set<string>()
    for (const remote of pulled) {
      const key = rowKey(remote.table, remote.id)
      const local = this.rows.get(key)
      const merged = mergeFields(local, remote)
      if (isDeleted(merged) && isDeleted(remote)) {
        if (local) {
          this.rows.delete(key)
          drop.push(key)
        }
        if (remote.table === 'books') gone.add(remote.id)
        continue
      }
      if (local && sameRow(local, merged)) continue
      const { seq: _seq, ...row } = merged
      void _seq
      this.rows.set(key, row)
      put.push(row)
    }
    // A book removed for good takes the rows that hang off it, as on the phone.
    if (gone.size > 0) {
      for (const [key, row] of this.rows) {
        const book = row.table === 'ebooks' ? row.id : row.fields.book_id
        if ((row.table === 'quotes' || row.table === 'bookmarks' || row.table === 'ebooks') &&
          typeof book === 'string' && gone.has(book)) {
          this.rows.delete(key)
          drop.push(key)
        }
      }
    }
    const moved = last > this.seq
    if (moved) this.seq = last
    // In one transaction, puts before drops: a quote pulled with its book's deletion ends up dropped, as in memory.
    if (put.length || drop.length || moved) this.persist({ put, drop, seq: this.seq })
    if (put.length || drop.length) this.dataChanged()
  }

  // ---- helpers ----

  private persist(w: Parameters<LocalDb['write']>[0]) {
    const db = this.db
    if (!db) return
    this.pendingWrites++
    this.writes = this.writes.then(() => db.write(w)).catch((e) => {
      console.error('LibraryStore: could not write the cache', e)
    }).then(() => {
      this.pendingWrites--
    })
  }

  private setStatus(next: SaveStatus) {
    const cur = this._status
    const same = cur === next || (typeof cur === 'object' && typeof next === 'object' &&
      JSON.stringify(cur) === JSON.stringify(next))
    if (same) return
    this._status = next
    this.emit()
  }

  private dataChanged() {
    this.booksMemo = null
    this.quotesMemo.clear()
    this.emit()
  }

  private emit() {
    for (const cb of [...this.listeners]) {
      try {
        cb()
      } catch (e) {
        console.error('LibraryStore: a listener failed', e)
      }
    }
  }

  private asBook(row: SyncRow): Book {
    let b = this.bookCache.get(row)
    if (!b) {
      b = rowToBook(row)
      this.bookCache.set(row, b)
    }
    return b
  }

  private asQuote(row: SyncRow): Quote {
    let q = this.quoteCache.get(row)
    if (!q) {
      q = rowToQuote(row)
      this.quoteCache.set(row, q)
    }
    return q
  }
}
