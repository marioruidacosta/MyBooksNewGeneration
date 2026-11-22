import type { Book, Status } from '../sync/model'
import type { PendingPublish, StoredPublish } from '../sync/queue'
import type { LibraryStore } from '../sync/store'

/** "Don't share this book": one synced preference per book, value '1'. The server enforces it too. */
export const SHARE_EXCLUDED_PREFIX = 'share_excluded.'

const excludedKey = (id: string) => SHARE_EXCLUDED_PREFIX + id

/**
 * Synced once every exclusion is in the cloud: an updated phone sets it after uploading the ones it kept to itself, and
 * the site when the community is joined here. Until then a book the server doesn't list may be private on a phone.
 */
export const SHARE_EXCLUSIONS_SYNCED = 'share_exclusions.synced'

/** The server's limit for a shared title or authors. */
const MAX_SHARED_TEXT = 300

/** The server's limit for a shared blurb. */
export const BLURB_MAX = 600

const SPACES = /\s+/g

/** The start of [text], never ending in half an emoji (which the server would refuse). */
function cut(text: string, max: number): string {
  const start = text.slice(0, max)
  return /[\uD800-\uDBFF]$/.test(start) ? start.slice(0, -1) : start
}

/** The start of a book's description for its feed card: whitespace collapsed, at most [BLURB_MAX]; null if blank. */
export function blurbOf(description: string | null): string | null {
  const collapsed = description?.replace(SPACES, ' ').trim()
  const text = collapsed === undefined ? '' : cut(collapsed, BLURB_MAX).trim()
  return text ? text : null
}

const DB_STATUS: Record<Status, string> = {
  READING: 'reading',
  READ: 'read',
  TO_READ: 'to_read',
  WISHLIST: 'to_buy',
}

/** `publish_book`'s `book` argument (the phone's `PublicBookState.toBookJson`). */
export type PublicState = {
  id: string
  isbn: string | null
  title: string
  authors: string
  cover_url: string | null
  status: string
  rating: number | null
  progress: number | null
  review: string
  blurb: string | null
  pages: number | null
  current_page: number | null
}

/** What the community sees of this book. Cover photos taken in the app are local files, so they're left out. */
export function publicState(book: Book): PublicState {
  return {
    id: book.id,
    isbn: book.isbn,
    title: cut(book.title, MAX_SHARED_TEXT),
    authors: cut(book.authors, MAX_SHARED_TEXT),
    cover_url: book.coverUrl !== null && book.coverUrl.startsWith('https://') ? book.coverUrl : null,
    status: DB_STATUS[book.status],
    rating: book.rating,
    progress: book.progressPercent,
    review: book.review,
    blurb: blurbOf(book.description),
    pages: book.pageCount,
    current_page: book.currentPage,
  }
}

/** Whether anything the community sees differs (notes, dates and the like don't count). */
export function publicFieldsDiffer(a: Book, b: Book): boolean {
  return (
    a.status !== b.status || a.rating !== b.rating || a.progressPercent !== b.progressPercent ||
    a.review !== b.review || a.title !== b.title || a.authors !== b.authors || a.coverUrl !== b.coverUrl ||
    a.description !== b.description || a.pageCount !== b.pageCount || a.currentPage !== b.currentPage
  )
}

/** The Supabase client as the publisher uses it (the real `supabase` fits). */
export type RpcClient = {
  rpc(
    fn: string,
    args?: Record<string, unknown>,
  ): PromiseLike<{ data: unknown; error: { message: string; code?: string } | null }>
}

export type PublisherOptions = {
  store: Pick<
    LibraryStore,
    'onLocalChangeWithOrigin' | 'loadPublishes' | 'putPublish' | 'deletePublish' | 'book' | 'pref' | 'setPref' | 'isQueued'
  >
  client: RpcClient
  /** Waits after the last change before sending (dragging a slider sends one update); the phone waits 2 s. */
  debounceMs?: number
  /** Waits after a failed send, the last one repeating. */
  retryDelays?: number[]
  /** The account this publisher posts for. */
  owner: string
  /**
   * The account signed in now: its id, null when a successful read finds no session, or 'unknown' when the session
   * can't be read (an error, offline). Nothing is sent unless it is [owner]; 'unknown' waits, anything else drops.
   */
  currentOwner: () => string | null | 'unknown' | Promise<string | null | 'unknown'>
  /**
   * Whether this account shares its activity: false with no community profile or with sharing off, 'unknown' when it
   * can't be read. Left out, the server alone decides.
   */
  readSharing?: () => Promise<boolean | 'unknown'>
}

type Entry = PendingPublish

const RETRY_DELAYS = [5_000, 15_000, 60_000, 300_000]

/** The server refusing this book's data (bad value, not allowed): sending it again can't work. */
const isRefusal = (code: string | undefined) => code !== undefined && /^(22|23|42|P0)/.test(code)

/**
 * Posts the site's changes to the community exactly as the phone does (`Publisher.kt`, `ShareTracker.kt`):
 * - A change made to a book by hand publishes unless the book is excluded ("Don't share this book", synced between
 *   devices, see [setExcluded]). That takes the account's exclusions being in the cloud ([SHARE_EXCLUSIONS_SYNCED]);
 *   until they are, and for changes to many books at once, only books the server already has are posted. A book counts
 *   as shared when the server lists it in `my_shared_book_ids()` (read at load, at the start of every send pass, and
 *   after each publish; when that read fails nothing is decided from the old list and the change waits) or the site
 *   just created it.
 * - Only a change to what the community sees publishes, sent with the state from before the batch of changes so the
 *   server creates the right feed entries; changes arriving together go as one. Removing a shared book unpublishes it.
 * - Changes pulled from a phone never publish (the store tells only of edits made here), and bulk writes tagged
 *   `import` (backup and Goodreads imports, filling in details) don't, as on the phone.
 * - A failed send is retried (5 s, 15 s, 60 s, then 300 s, and when the connection returns), keeping the first
 *   "before". A refusal (the server rejects the data) is logged and dropped, and a book the site created is sent
 *   again once what the community sees of it changes (as on the phone). The local edit is never touched.
 * - Every send first checks the session is still this publisher's account.
 * - Changes waiting at a closed tab are kept in the browser's copy of the library and sent at the next visit of the same
 *   account (dropped for any other). A post for a book excluded meanwhile goes nowhere.
 */
export class Publisher {
  private readonly client: RpcClient
  private readonly debounceMs: number
  private readonly retryDelays: number[]
  private readonly store: PublisherOptions['store']
  private readonly owner: string | undefined
  private readonly currentOwner: PublisherOptions['currentOwner'] | undefined
  private readonly readSharing: PublisherOptions['readSharing'] | undefined
  /** Whether the account shares, as last read; null until then. */
  private sharing: boolean | null = null

  private ids = new Set<string>()
  private idsKnown = false
  /** Created on the site, not yet listed by the server. */
  private created = new Set<string>()
  private pending = new Map<string, Entry>()
  /** The public state of a created book the server refused, until what the community sees of it changes. */
  private refused = new Map<string, Book>()
  /** The book whose send is in flight. */
  private sendingId: string | null = null
  private listeners = new Set<() => void>()

  private stopStore: (() => void) | null = null
  private timer: ReturnType<typeof setTimeout> | null = null
  private retryTimer: ReturnType<typeof setTimeout> | null = null
  private running: Promise<void> | null = null
  private failures = 0
  private stopped = false

  constructor(options: PublisherOptions) {
    this.store = options.store
    this.client = options.client
    this.debounceMs = options.debounceMs ?? 2_000
    this.retryDelays = options.retryDelays?.length ? options.retryDelays : RETRY_DELAYS
    this.owner = options.owner
    this.currentOwner = options.currentOwner
    this.readSharing = options.readSharing
  }

  /** Starts listening to the store and reads the shared ids; resolves when that first read is over. */
  async start(): Promise<void> {
    await this.restore()
    this.stopStore = this.store.onLocalChangeWithOrigin((...change) => {
      if (change[0] === 'books') this.onBook(change[1], change[2], change[3])
    })
    if (typeof window !== 'undefined') window.addEventListener('online', this.onOnline)
    await this.refreshSharing()
    if (!(await this.refreshIds())) this.scheduleRetry()
    else if (this.pending.size > 0 && !this.stopped) this.schedule()
  }

  stop(): void {
    this.stopped = true
    this.stopStore?.()
    this.stopStore = null
    if (this.timer) clearTimeout(this.timer)
    if (this.retryTimer) clearTimeout(this.retryTimer)
    this.timer = this.retryTimer = null
    if (typeof window !== 'undefined') window.removeEventListener('online', this.onOnline)
  }

  /** Whether the community has this book (or the site just posted it); null until the shared books are known. */
  isShared(id: string): boolean | null {
    if (this.sharing === false || this.isExcluded(id)) return false
    if (this.created.has(id)) return true
    return this.idsKnown ? this.ids.has(id) : null
  }

  /** Reads what the server has shared again (sharing was just turned on or off). */
  async refresh(): Promise<void> {
    await this.refreshSharing()
    await this.refreshIds()
    this.notify()
  }

  /** "Don't share this book", as set on any device. */
  isExcluded(id: string): boolean {
    return this.store.pref(excludedKey(id)) === '1'
  }

  /**
   * Sets "Don't share this book" for every device. Excluding takes the public copy down (the server does too, when the
   * preference reaches it). Sharing again posts the book as it is, measured from itself, so no feed entry appears.
   */
  setExcluded(id: string, excluded: boolean): void {
    if (excluded === this.isExcluded(id)) return
    this.store.setPref(excludedKey(id), excluded ? '1' : null)
    if (excluded) {
      this.refused.delete(id)
      this.queueUnpublish(id)
    } else {
      const book = this.store.book(id)
      if (book) this.queuePublish(book, book, false, { reshare: true })
    }
    this.notify()
  }

  /**
   * Whether changes are waiting to be sent that closing the tab could lose. A change to an excluded book will never be
   * posted, so it doesn't count. (They are kept
   * in the browser's copy too, and go at the next visit; this is for the person who would rather they went now.)
   */
  hasPending(): boolean {
    // Not sharing: what's waiting will be dropped, not sent.
    if (this.sharing === false) return false
    // Offline nothing can go out, and what waits is in the browser's copy: it goes at the next visit.
    if (typeof navigator !== 'undefined' && navigator.onLine === false) return false
    for (const [id, entry] of this.pending) {
      if (entry.kind === 'publish' && this.isExcluded(id)) continue
      // Which books are shared isn't known yet: any change might be.
      if (!this.idsKnown) return true
      if (entry.kind === 'publish' && this.mayPost(entry)) return true
      if (this.ids.has(id)) return true
    }
    return false
  }

  /** Whether a post may put its book in the community even though the server doesn't have it. */
  private mayPost(entry: Extract<Entry, { kind: 'publish' }>): boolean {
    return entry.created || entry.reshare === true ||
      (entry.mayShare === true && this.store.pref(SHARE_EXCLUSIONS_SYNCED) === '1')
  }

  /** Called when the shared books change. Returns the way to stop. */
  subscribe = (cb: () => void): (() => void) => {
    this.listeners.add(cb)
    return () => {
      this.listeners.delete(cb)
    }
  }

  /** Resolves once nothing is waiting to be sent (retry waits included). */
  async idle(): Promise<void> {
    while (!this.stopped && (this.timer !== null || this.running !== null || this.pending.size > 0)) {
      await new Promise((r) => setTimeout(r, 1))
    }
  }

  /**
   * Takes up the posts a closed tab left waiting (the store hands over only those of tabs that are gone, each once;
   * another account's are forgotten). A post for a book that is gone (the phone deleted it meanwhile) is dropped, as
   * a ghost of it must not reach the community; a removal is still sent.
   */
  private async restore(): Promise<void> {
    if (this.owner === undefined) return
    let kept: StoredPublish[]
    try {
      kept = await this.store.loadPublishes(this.owner)
    } catch {
      return
    }
    if (this.stopped) return
    for (const { id, entry } of kept) {
      if (entry.kind === 'publish' && !this.store.book(id)) {
        this.store.deletePublish(id)
        continue
      }
      this.pending.set(id, entry)
      if (entry.kind === 'publish' && entry.created) this.created.add(id)
    }
  }

  private setPending(id: string, entry: Entry) {
    this.pending.set(id, entry)
    this.save(id, entry)
  }

  private deletePending(id: string) {
    if (this.pending.delete(id)) this.forget(id)
  }

  private clearPending() {
    const ids = [...this.pending.keys()]
    this.pending.clear()
    for (const id of ids) this.forget(id)
  }

  /** Keeps a waiting post in the browser's copy of the library (only this tab's entry for the book is touched). */
  private save(id: string, entry: Entry) {
    if (this.stopped || this.owner === undefined) return
    this.store.putPublish({ id, owner: this.owner, entry })
  }

  private forget(id: string) {
    if (this.stopped || this.owner === undefined) return
    this.store.deletePublish(id)
  }

  private onBook(before: Book | null, after: Book | null, origin: string | undefined) {
    if (origin === 'import') return
    if (!after) {
      if (before) this.queueUnpublish(before.id)
      return
    }
    if (this.isExcluded(after.id)) return
    if (!before && origin === 'restore') {
      // Undo of a removal. Not a book created on the site, however it looks to the store (it was "deleted", now it
      // isn't): posting it as new would put a book the phone excluded into the community, bring back the copy a Tidy
      // merge dropped, or give a book already taken down a fresh feed entry. So: when its unpublish hasn't gone out
      // yet, cancel it and the book stays shared with nothing posted; otherwise publish only if the server still
      // lists it (created: false), which after a sent unpublish it doesn't. The phone shares it again if wanted.
      if (this.sendingId !== after.id && this.pending.get(after.id)?.kind === 'unpublish') {
        this.deletePending(after.id)
        return
      }
      this.queuePublish(null, after, false)
      return
    }
    if (!before) {
      this.created.add(after.id)
      this.refused.delete(after.id)
      this.queuePublish(null, after, true)
      return
    }
    const refused = this.refused.get(after.id)
    if (refused) {
      // The server refused this book: only a change to what it sees is worth sending again.
      if (!publicFieldsDiffer(refused, after)) return
      this.refused.delete(after.id)
      this.queuePublish(before, after, true)
      return
    }
    if (publicFieldsDiffer(before, after)) {
      this.queuePublish(before, after, false, origin === undefined ? { mayShare: true } : {})
    }
  }

  private queuePublish(
    before: Book | null, after: Book, created: boolean, flags: { mayShare?: boolean; reshare?: boolean } = {},
  ) {
    const found = this.pending.get(after.id)
    const held = found?.kind === 'publish' ? found : null
    // "Before" is the state before this batch of changes: the first one stays. An undone removal has none, so a real
    // change after it is measured from just before that change.
    const first = held && (held.before !== null || held.created || before === null) ? held.before : before
    const entry: Extract<Entry, { kind: 'publish' }> = {
      kind: 'publish', before: first, after, created: (held?.created ?? false) || created,
      ...(held?.mayShare || flags.mayShare ? { mayShare: true } : {}),
      ...(held?.reshare || flags.reshare ? { reshare: true } : {}),
    }
    // Changed and changed back before anything was sent: nothing to say.
    if (!entry.created && !entry.reshare && entry.before && !publicFieldsDiffer(entry.before, after)) {
      this.deletePending(after.id)
      return
    }
    this.setPending(after.id, entry)
    this.schedule()
  }

  private queueUnpublish(id: string) {
    const held = this.pending.get(id)
    this.created.delete(id)
    this.refused.delete(id)
    // Not on the way: it never reached the server. On the way: it may land, so take it down once the send is over.
    if (this.sendingId !== id && held?.kind === 'publish' && held.created) {
      this.deletePending(id)
      return
    }
    this.setPending(id, { kind: 'unpublish' })
    this.schedule()
  }

  private schedule() {
    if (this.stopped) return
    if (this.timer) clearTimeout(this.timer)
    this.timer = setTimeout(() => {
      this.timer = null
      void this.flush()
    }, this.debounceMs)
  }

  private onOnline = () => {
    if (this.retryTimer) {
      clearTimeout(this.retryTimer)
      this.retryTimer = null
    }
    if (!this.idsKnown) void this.refreshIds()
    if (this.pending.size > 0) void this.flush()
  }

  private scheduleRetry() {
    if (this.stopped || this.retryTimer) return
    const wait = this.retryDelays[Math.min(this.failures, this.retryDelays.length - 1)]
    this.failures++
    this.retryTimer = setTimeout(() => {
      this.retryTimer = null
      if (!this.idsKnown) void this.refreshIds().then((ok) => (ok ? undefined : this.scheduleRetry()))
      if (this.pending.size > 0) void this.flush()
    }, wait)
  }

  private flush(): Promise<void> {
    if (this.running) return this.running
    const run = this.drain().finally(() => {
      this.running = null
      // Something came in after the last look.
      if (!this.stopped && this.pending.size > 0 && this.timer === null && this.retryTimer === null) this.schedule()
    })
    this.running = run
    return run
  }

  private async drain(): Promise<void> {
    while (!this.stopped && this.pending.size > 0) {
      if (!(await this.sessionIsOurs()) || this.stopped) return
      // Read at every pass, as sharing can be turned on or off on another device. Not sharing: nothing goes out, and
      // what was waiting is dropped, as the phone does.
      const sharing = await this.refreshSharing()
      if (this.stopped) return
      if (sharing === 'unknown') {
        this.scheduleRetry()
        return
      }
      if (!sharing) {
        this.created.clear()
        this.clearPending()
        this.notify()
        return
      }
      // Read fresh at every pass: a phone may have excluded or shared books since. A failed read decides nothing.
      if (!(await this.refreshIds())) {
        this.scheduleRetry()
        return
      }
      if (this.stopped) return
      let published = false
      let waiting = false
      for (const [id, entry] of [...this.pending]) {
        if (this.stopped) return
        // Excluded (here or on another device), even since it was queued: never posted.
        if (entry.kind === 'publish' && this.isExcluded(id)) {
          this.created.delete(id)
          this.deletePending(id)
          continue
        }
        // Not on the server, and not a change that may put it there.
        if (entry.kind === 'publish' && !this.ids.has(id) && !this.mayPost(entry)) {
          this.deletePending(id)
          continue
        }
        // Shared again: the server would ignore it until it has heard the exclusion is gone.
        if (entry.kind === 'publish' && entry.reshare && this.store.isQueued('prefs', excludedKey(id))) {
          waiting = true
          continue
        }
        if (entry.kind === 'unpublish' && !this.ids.has(id)) {
          this.deletePending(id)
          continue
        }
        if (!(await this.sessionIsOurs()) || this.stopped) return
        // The session check was a wait: what is queued for this book may have changed.
        if (this.pending.get(id) !== entry) continue
        // What is sent is the book as it is now (a restored post may be old), measured from the saved "before".
        let current: Book | undefined
        if (entry.kind === 'publish') {
          current = this.store.book(id)
          if (!current) {
            // Deleted meanwhile: nothing to post.
            this.created.delete(id)
            this.deletePending(id)
            continue
          }
          if (!entry.created && !entry.reshare && entry.before && !publicFieldsDiffer(entry.before, current)) {
            this.deletePending(id)
            continue
          }
        }
        this.sendingId = id
        let outcome: 'sent' | 'dropped' | 'retry'
        try {
          outcome = await this.send(id, entry, current)
        } finally {
          this.sendingId = null
        }
        if (outcome === 'retry') {
          this.scheduleRetry()
          return
        }
        const newer = this.pending.get(id)
        if (newer === entry) this.deletePending(id)
        else if (newer?.kind === 'publish' && entry.kind === 'publish') {
          // Changed while this went out: the server has it now (when it worked), so the next send is measured from
          // there, and the book is no longer "new": a delete must take it down.
          this.setPending(id, {
            ...newer, before: current ?? entry.after, created: outcome === 'sent' ? false : newer.created,
          })
        }
        if (outcome === 'sent') {
          this.refused.delete(id)
          this.failures = 0
          if (entry.kind === 'publish') {
            this.created.delete(id)
            this.ids.add(id)
            published = true
          } else {
            this.ids.delete(id)
          }
        } else if (entry.kind === 'publish') {
          // Refused: a book the site created stays "shared" here, and goes again once what it shows changes.
          if (entry.created) this.refused.set(id, current ?? entry.after)
        } else {
          this.created.delete(id)
        }
        this.notify()
      }
      if (published) await this.refreshIds()
      if (waiting) {
        this.scheduleRetry()
        return
      }
    }
  }

  private async send(id: string, entry: Entry, current: Book | undefined): Promise<'sent' | 'dropped' | 'retry'> {
    try {
      const { error } =
        entry.kind === 'publish'
          ? await this.client.rpc('publish_book', {
              book: publicState(current ?? entry.after),
              before: entry.before ? publicState(entry.before) : null,
            })
          : await this.client.rpc('unpublish_book', { id })
      if (!error) return 'sent'
      if (isRefusal(error.code)) {
        console.warn(`Publisher: the server refused book ${id}: ${error.message}`)
        return 'dropped'
      }
      return 'retry'
    } catch {
      return 'retry'
    }
  }

  /**
   * Whether the signed-in account is this publisher's. Another account or no session (a successful read) drops
   * everything waiting; a session that can't be read keeps it and tries again later. Without an owner, nothing is
   * ever sent.
   */
  private async sessionIsOurs(): Promise<boolean> {
    if (this.owner === undefined || !this.currentOwner) return false
    let now: string | null | 'unknown'
    try {
      now = await this.currentOwner()
    } catch {
      now = 'unknown'
    }
    if (this.stopped) return false
    if (now === this.owner) return true
    if (now === 'unknown') {
      this.scheduleRetry()
      return false
    }
    this.clearPending()
    this.notify()
    return false
  }

  /** Reads whether the account shares; 'unknown' keeps what was known. */
  private async refreshSharing(): Promise<boolean | 'unknown'> {
    if (!this.readSharing) return true
    let answer: boolean | 'unknown'
    try {
      answer = await this.readSharing()
    } catch {
      answer = 'unknown'
    }
    if (answer !== 'unknown') this.sharing = answer
    return answer
  }

  /** Reads the shared ids; false when that failed (the ones known stay). */
  private async refreshIds(): Promise<boolean> {
    try {
      const { data, error } = await this.client.rpc('my_shared_book_ids')
      if (error || !Array.isArray(data) || this.stopped) return false
      this.ids = new Set(data.map(String))
      this.idsKnown = true
      this.notify()
      return true
    } catch {
      return false
    }
  }

  private notify() {
    for (const cb of [...this.listeners]) cb()
  }
}
