import type { Book, Status } from '../sync/model'
import type { LibraryStore } from '../sync/store'

/** The server's limit for a shared title or authors. */
const MAX_SHARED_TEXT = 300

/** The server's limit for a shared blurb. */
export const BLURB_MAX = 600

const SPACES = /\s+/g

/** The start of a book's description for its feed card: whitespace collapsed, at most [BLURB_MAX]; null if blank. */
export function blurbOf(description: string | null): string | null {
  const text = description?.replace(SPACES, ' ').trim().slice(0, BLURB_MAX).trim()
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
    title: book.title.slice(0, MAX_SHARED_TEXT),
    authors: book.authors.slice(0, MAX_SHARED_TEXT),
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
  store: Pick<LibraryStore, 'onLocalChangeWithOrigin'>
  client: RpcClient
  /** Waits after the last change before sending (dragging a slider sends one update); the phone waits 2 s. */
  debounceMs?: number
  /** Waits after a failed send, the last one repeating. */
  retryDelays?: number[]
}

type Entry =
  | { kind: 'publish'; before: Book | null; after: Book; created: boolean }
  | { kind: 'unpublish' }

const RETRY_DELAYS = [5_000, 15_000, 60_000, 300_000]

/** The server refusing this book's data (bad value, not allowed): sending it again can't work. */
const isRefusal = (code: string | undefined) => code !== undefined && /^(22|23|42|P0)/.test(code)

/**
 * Posts the site's changes to the community exactly as the phone does (`Publisher.kt`, `ShareTracker.kt`):
 * - A book counts as shared when the server lists it in `my_shared_book_ids()` (read at load and after each
 *   publish) or the site just created it. There is no per-book switch on the site.
 * - Only a change to what the community sees publishes, sent with the state from before the batch of changes so the
 *   server creates the right feed entries; changes arriving together go as one. Removing a shared book unpublishes it.
 * - Changes pulled from a phone never publish (the store tells only of edits made here), and bulk writes tagged
 *   `import` (backup and Goodreads imports, filling in details) don't, as on the phone.
 * - A failed send is retried (5 s, 15 s, 60 s, then 300 s, and when the connection returns), keeping the first
 *   "before". A refusal (the server rejects the data) is logged and dropped. The local edit is never touched.
 *   Changes waiting at a closed tab are not remembered: the phone stays the source of truth for what's shared.
 */
export class Publisher {
  private readonly client: RpcClient
  private readonly debounceMs: number
  private readonly retryDelays: number[]
  private readonly store: Pick<LibraryStore, 'onLocalChangeWithOrigin'>

  private ids = new Set<string>()
  private idsKnown = false
  /** Created on the site, not yet listed by the server. */
  private created = new Set<string>()
  private pending = new Map<string, Entry>()
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
  }

  /** Starts listening to the store and reads the shared ids; resolves when that first read is over. */
  async start(): Promise<void> {
    this.stopStore = this.store.onLocalChangeWithOrigin((...change) => {
      if (change[0] === 'books') this.onBook(change[1], change[2], change[3])
    })
    if (typeof window !== 'undefined') window.addEventListener('online', this.onOnline)
    if (!(await this.refreshIds())) this.scheduleRetry()
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

  /** Whether the community has this book (or the site just posted it). */
  isShared(id: string): boolean {
    return this.ids.has(id) || this.created.has(id)
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

  private onBook(before: Book | null, after: Book | null, origin: string | undefined) {
    if (origin === 'import') return
    if (!after) {
      if (before) this.queueUnpublish(before.id)
      return
    }
    if (!before) {
      this.created.add(after.id)
      this.queuePublish(null, after, true)
      return
    }
    if (publicFieldsDiffer(before, after)) this.queuePublish(before, after, false)
  }

  private queuePublish(before: Book | null, after: Book, created: boolean) {
    const held = this.pending.get(after.id)
    // "Before" is the state before this batch of changes: the first one stays.
    const entry: Entry =
      held?.kind === 'publish'
        ? { kind: 'publish', before: held.before, after, created: held.created || created }
        : { kind: 'publish', before, after, created }
    // Changed and changed back before anything was sent: nothing to say.
    if (!entry.created && entry.before && !publicFieldsDiffer(entry.before, after)) {
      this.pending.delete(after.id)
      return
    }
    this.pending.set(after.id, entry)
    this.schedule()
  }

  private queueUnpublish(id: string) {
    const held = this.pending.get(id)
    this.created.delete(id)
    if (held?.kind === 'publish' && held.created) {
      // Never reached the server.
      this.pending.delete(id)
      return
    }
    if (this.idsKnown && !this.ids.has(id)) {
      this.pending.delete(id)
      return
    }
    this.pending.set(id, { kind: 'unpublish' })
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
      if (!this.idsKnown && !(await this.refreshIds())) {
        this.scheduleRetry()
        return
      }
      let published = false
      for (const [id, entry] of [...this.pending]) {
        if (this.stopped) return
        // Not shared (a phone excluded it, or sharing is off): not for the site to post.
        if (entry.kind === 'publish' && !entry.created && !this.ids.has(id)) {
          this.pending.delete(id)
          continue
        }
        if (entry.kind === 'unpublish' && !this.ids.has(id)) {
          this.pending.delete(id)
          continue
        }
        const outcome = await this.send(id, entry)
        if (outcome === 'retry') {
          this.scheduleRetry()
          return
        }
        const newer = this.pending.get(id)
        if (newer === entry) this.pending.delete(id)
        else if (newer?.kind === 'publish' && entry.kind === 'publish') {
          // Changed while this went out: the server has it now, so the next send is measured from there.
          this.pending.set(id, { ...newer, before: entry.after })
        }
        if (outcome === 'sent') {
          this.failures = 0
          if (entry.kind === 'publish') {
            this.created.delete(id)
            this.ids.add(id)
            published = true
          } else {
            this.ids.delete(id)
          }
        } else {
          this.created.delete(id)
        }
        this.notify()
      }
      if (published) await this.refreshIds()
    }
  }

  private async send(id: string, entry: Entry): Promise<'sent' | 'dropped' | 'retry'> {
    try {
      const { error } =
        entry.kind === 'publish'
          ? await this.client.rpc('publish_book', {
              book: publicState(entry.after),
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
