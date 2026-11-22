import { describe, expect, it } from 'vitest'
import type { ActivityKind, FeedEntry, FriendWithBook, Person } from './model'
import {
  GROUP_WINDOW_MS, actionText, friendWithBookText, groupFeed, groupText, normalizeUsername, relativeTime, starsLabel,
  starsText, usernameProblem,
} from './text'

const ana: Person = { id: 'ana', username: 'ana', displayName: 'Ana', avatarUrl: null, visibility: 'friends' }
const rui: Person = { ...ana, id: 'rui', username: 'rui', displayName: 'Rui' }
const NOW = Date.UTC(2026, 10, 22, 12)

let nextId = 1
function entry(kind: ActivityKind, over: Partial<FeedEntry> & { hoursAgo?: number } = {}): FeedEntry {
  const { hoursAgo = 0, ...rest } = over
  const id = nextId++
  const createdAt = NOW - hoursAgo * 3_600_000
  return {
    id, actor: ana, kind, rating: null, progress: null, createdAt, createdAtText: new Date(createdAt).toISOString(),
    book: { shareId: `b${id}`, isbn: null, title: 'Dune', authors: 'Herbert', coverUrl: null, blurb: null, pages: null, currentPage: null },
    review: null, likeCount: 0, likedByMe: false, ...rest,
  }
}

describe('usernames', () => {
  it('normalizesWhatWasTyped', () => expect(normalizeUsername('  Ana.Silva ')).toBe('ana.silva'))
  it.each(['ana', 'a_b.c9', 'a'.repeat(20)])('accepts %s', (u) => expect(usernameProblem(u)).toBeNull())
  it.each(['ab', 'a'.repeat(21), 'Ana', 'a b', 'ána', 'a-b'])('refuses %s', (u) => expect(usernameProblem(u)).not.toBeNull())
})

describe('feed text', () => {
  it.each<[ActivityKind, string]>([
    ['started', 'started reading'], ['finished', 'finished'], ['want_to_read', 'wants to read'],
    ['want_to_buy', 'wants to buy'], ['reviewed', 'reviewed'], ['rated', 'rated it'],
  ])('%s', (kind, text) => expect(actionText(entry(kind))).toBe(text))

  it('progressPrefersThePage', () => {
    const base = entry('progress', { progress: 53 })
    expect(actionText({ ...base, book: { ...base.book, currentPage: 182, pages: 344 } })).toBe('is on page 182 of 344')
  })

  it('progressFallsBackToThePercentWhenThePageMakesNoSense', () => {
    const base = entry('progress', { progress: 53 })
    expect(actionText({ ...base, book: { ...base.book, currentPage: 400, pages: 344 } })).toBe('is 53% through')
    expect(actionText(base)).toBe('is 53% through')
  })

  it('progressWithNothingKnown', () => expect(actionText(entry('progress'))).toBe('is reading'))

  it('stars', () => {
    expect(starsText(4)).toBe('★★★★☆')
    expect(starsText(null)).toBeNull()
    expect(starsText(9)).toBe('★★★★★')
    expect(starsLabel(4)).toBe('Rated 4 out of 5')
  })

  it.each<[FriendWithBook['status'], number | null, string]>([
    ['READ', null, 'read it'], ['READING', 40, 'is reading it · 40%'], ['READING', null, 'is reading it'],
    ['TO_READ', null, 'wants to read it'], ['WISHLIST', null, 'wants to buy it'],
  ])('a friend with %s', (status, progress, text) => {
    expect(friendWithBookText({ person: ana, status, rating: null, progress, review: '' })).toBe(text)
  })

  it('relativeTimes', () => {
    expect(relativeTime(NOW - 30_000, NOW)).toBe('just now')
    expect(relativeTime(NOW - 5 * 60_000, NOW)).toBe('5 min ago')
    expect(relativeTime(NOW - 2 * 3_600_000, NOW)).toBe('2 h ago')
    expect(relativeTime(NOW - 3 * 86_400_000, NOW)).toBe('3 d ago')
    expect(relativeTime(NOW - 30 * 86_400_000, NOW)).toMatch(/2026/)
  })

  it('groupText', () => {
    expect(groupText('want_to_read', 5)).toBe('added 5 books to To read')
    expect(groupText('want_to_buy', 2)).toBe('added 2 books to To buy')
  })
})

describe('grouping', () => {
  const kinds = (items: ReturnType<typeof groupFeed>) =>
    items.map((i) => (i.type === 'group' ? `group${i.entries.length}` : i.entry.kind))

  it('severalBooksAddedTogetherMakeOneCard', () => {
    expect(kinds(groupFeed([entry('want_to_read'), entry('want_to_read', { hoursAgo: 1 }), entry('want_to_read', { hoursAgo: 2 })])))
      .toEqual(['group3'])
  })

  it('aRunOfOneStaysASingleCard', () => expect(kinds(groupFeed([entry('want_to_read')]))).toEqual(['want_to_read']))

  it('otherKindsNeverGroup', () => {
    expect(kinds(groupFeed([entry('finished'), entry('finished', { hoursAgo: 1 })]))).toEqual(['finished', 'finished'])
  })

  it('anotherPersonBreaksTheRun', () => {
    expect(kinds(groupFeed([entry('want_to_read'), entry('want_to_read', { actor: rui, hoursAgo: 1 }), entry('want_to_read', { hoursAgo: 2 })])))
      .toEqual(['want_to_read', 'want_to_read', 'want_to_read'])
  })

  it('toReadAndToBuyDoNotMix', () => {
    expect(kinds(groupFeed([entry('want_to_read'), entry('want_to_buy', { hoursAgo: 1 })]))).toEqual(['want_to_read', 'want_to_buy'])
  })

  it('theWindowIsFromThePreviousEntryNotTheFirst', () => {
    expect(kinds(groupFeed([entry('want_to_read'), entry('want_to_read', { hoursAgo: 20 }), entry('want_to_read', { hoursAgo: 40 })])))
      .toEqual(['group3'])
  })

  it('moreThanADayApartSplits', () => {
    const first = entry('want_to_read')
    const later = entry('want_to_read', { createdAt: first.createdAt - GROUP_WINDOW_MS - 1 })
    expect(kinds(groupFeed([first, later]))).toEqual(['want_to_read', 'want_to_read'])
  })

  it('keysStayTheSameAsMorePagesLoad', () => {
    const a = entry('want_to_read')
    const b = entry('want_to_read', { hoursAgo: 1 })
    expect(groupFeed([a, b])[0].key).toBe(groupFeed([a, b, entry('want_to_read', { hoursAgo: 2 })])[0].key)
  })
})
