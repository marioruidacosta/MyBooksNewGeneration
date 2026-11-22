import { formatDay } from '../text/dates'
import type { ActivityKind, FeedEntry, FriendWithBook, Person, SuggestedReader } from './model'

const USERNAME = /^[a-z0-9_.]{3,20}$/

/** What was typed, as a username: trimmed and lower-case. */
export const normalizeUsername = (raw: string): string => raw.trim().toLowerCase()

/** Why [username] isn't allowed (the database's own rule), or null when it is. */
export const usernameProblem = (username: string): string | null =>
  USERNAME.test(username) ? null : '3–20 characters: a–z, 0–9, _ and .'

/** What someone did, after their name. The stars of a rating go on their own line (see [starsText]). */
export function actionText(entry: FeedEntry): string {
  switch (entry.kind) {
    case 'started':
      return 'started reading'
    case 'finished':
      return 'finished'
    case 'want_to_read':
      return 'wants to read'
    case 'want_to_buy':
      return 'wants to buy'
    case 'reviewed':
      return 'reviewed'
    case 'rated':
      return 'rated it'
    case 'progress': {
      const { currentPage: page, pages } = entry.book
      if (page !== null && pages !== null && page <= pages) return `is on page ${page} of ${pages}`
      return entry.progress !== null ? `is ${entry.progress}% through` : 'is reading'
    }
  }
}

const clampStars = (rating: number) => Math.min(5, Math.max(0, rating))

/** A score as stars: 4 is "★★★★☆"; null for none. */
export const starsText = (rating: number | null): string | null =>
  rating === null ? null : '★'.repeat(clampStars(rating)) + '☆'.repeat(5 - clampStars(rating))

/** The stars as a screen reader says them. */
export const starsLabel = (rating: number | null): string | null =>
  rating === null ? null : `Rated ${clampStars(rating)} out of 5`

/** Books added this close together (each to the previous) by the same person make one card. */
export const GROUP_WINDOW_MS = 24 * 60 * 60 * 1000

export type FeedItem =
  | { type: 'single'; key: string; entry: FeedEntry }
  | { type: 'group'; key: string; actor: Person; kind: ActivityKind; entries: FeedEntry[] }

const groupable = (kind: ActivityKind) => kind === 'want_to_read' || kind === 'want_to_buy'

/**
 * The feed's entries (newest first) as cards: consecutive "wants to read" (or "wants to buy") entries by the same
 * person, each within a day of the previous one, make one card. Run on the whole loaded list, so that a run split by a
 * page boundary joins up.
 */
export function groupFeed(entries: FeedEntry[]): FeedItem[] {
  const items: FeedItem[] = []
  let run: FeedEntry[] = []
  const flush = () => {
    if (run.length === 1) items.push({ type: 'single', key: `e:${run[0].id}`, entry: run[0] })
    else if (run.length > 1) {
      items.push({ type: 'group', key: `g:${run[0].id}`, actor: run[0].actor, kind: run[0].kind, entries: run })
    }
    run = []
  }
  for (const entry of entries) {
    const last = run[run.length - 1]
    const joins = last !== undefined && groupable(entry.kind) && entry.kind === last.kind &&
      entry.actor.id === last.actor.id && last.createdAt - entry.createdAt <= GROUP_WINDOW_MS
    if (!joins) flush()
    if (groupable(entry.kind)) run.push(entry)
    else items.push({ type: 'single', key: `e:${entry.id}`, entry })
  }
  flush()
  return items
}

/** A grouped card's line, after the name. */
export const groupText = (kind: ActivityKind, count: number): string =>
  `added ${count} books to ${kind === 'want_to_buy' ? 'To buy' : 'To read'}`

/** A friend's line on a book's page, after their name. */
export function friendWithBookText(friend: FriendWithBook): string {
  switch (friend.status) {
    case 'READ':
      return 'read it'
    case 'READING':
      return friend.progress !== null ? `is reading it · ${friend.progress}%` : 'is reading it'
    case 'TO_READ':
      return 'wants to read it'
    case 'WISHLIST':
      return 'wants to buy it'
  }
}

const MINUTE = 60_000
const HOUR = 60 * MINUTE
const DAY = 24 * HOUR

/** "just now", "5 min ago", "2 h ago", "3 d ago", then the date. */
export function relativeTime(then: number, now: number = Date.now()): string {
  const elapsed = now - then
  if (elapsed < MINUTE) return 'just now'
  if (elapsed < HOUR) return `${Math.floor(elapsed / MINUTE)} min ago`
  if (elapsed < DAY) return `${Math.floor(elapsed / HOUR)} h ago`
  if (elapsed < 7 * DAY) return `${Math.floor(elapsed / DAY)} d ago`
  return formatDay(then)
}

/** What a suggested reader has in common with you. */
export function suggestionText(s: SuggestedReader): string {
  const parts: string[] = []
  if (s.sharedBooks > 0) {
    const books = s.sharedBooks === 1 ? '1 book' : `${s.sharedBooks} books`
    parts.push(s.topTitle ? `Also loved ${s.topTitle}${s.sharedBooks > 1 ? ` and ${s.sharedBooks - 1} more` : ''}` : `Loved ${books} you loved`)
  }
  if (s.mutualFriends > 0) {
    parts.push(s.mutualNames.length > 0
      ? `Friends with ${s.mutualNames.join(' and ')}${s.mutualFriends > s.mutualNames.length ? ` and ${s.mutualFriends - s.mutualNames.length} more` : ''}`
      : s.mutualFriends === 1 ? '1 friend in common' : `${s.mutualFriends} friends in common`)
  }
  return parts.join(' · ')
}
