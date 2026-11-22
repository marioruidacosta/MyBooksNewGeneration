import type { CommunityApi } from './api'
import type { BookSuggestion } from './model'

type InboxApi = Pick<CommunityApi, 'bookSuggestions' | 'markSuggestionsSeen' | 'closeSuggestion'>

const newestFirst = (a: BookSuggestion, b: BookSuggestion) => b.createdAt - a.createdAt || b.id - a.id

/** The books friends suggested to the signed-in person, and how many are new (the badge on Community). */
export class SuggestionInbox {
  private list: BookSuggestion[] = []
  private readonly listeners = new Set<() => void>()
  /** Reads are numbered: an older answer arriving after a newer one is dropped. */
  private asked = 0
  private answered = 0
  /** Closed here, or being closed: a read that started earlier may still list them. */
  private readonly closed = new Set<number>()
  /** Marked seen here: a read that started earlier may still call them new. */
  private readonly markedSeen = new Set<number>()

  constructor(private readonly api: InboxApi) {}

  subscribe = (listener: () => void) => {
    this.listeners.add(listener)
    return () => {
      this.listeners.delete(listener)
    }
  }

  items = () => this.list

  unseen = () => this.list.filter((s) => !s.seen).length

  private set(next: BookSuggestion[]) {
    this.list = next
    this.listeners.forEach((l) => l())
  }

  /** [rows] with what was done here applied, whatever the server had when it was read. */
  private shown(rows: BookSuggestion[]) {
    return rows
      .filter((s) => !this.closed.has(s.id))
      .map((s) => (this.markedSeen.has(s.id) && !s.seen ? { ...s, seen: true } : s))
  }

  /** Reads them again; when that fails, what's shown stays. */
  async refresh(): Promise<void> {
    const ask = ++this.asked
    let found: BookSuggestion[]
    try {
      found = await this.api.bookSuggestions()
    } catch {
      return
    }
    if (ask < this.answered) return
    this.answered = ask
    this.set(this.shown(found))
  }

  /** The person is looking at them: the badge clears here at once, and on their other devices. Only what's on screen. */
  markSeen() {
    const ids = this.list.filter((s) => !s.seen).map((s) => s.id)
    if (ids.length === 0) return
    ids.forEach((id) => this.markedSeen.add(id))
    this.set(this.shown(this.list))
    this.api.markSuggestionsSeen(ids).catch(() => {
      // Still new on the server: the badge comes back at the next refresh.
      ids.forEach((id) => this.markedSeen.delete(id))
    })
  }

  /** The book was added or dismissed. False when the server couldn't be told: it is shown again. */
  async close(id: number): Promise<boolean> {
    const closing = this.list.find((s) => s.id === id)
    if (!closing) return true
    this.closed.add(id)
    this.set(this.shown(this.list))
    try {
      await this.api.closeSuggestion(id)
      return true
    } catch {
      this.closed.delete(id)
      this.set([...this.list.filter((s) => s.id !== id), closing].sort(newestFirst))
      return false
    }
  }
}
