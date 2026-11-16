import { describe, expect, it } from 'vitest'
import { bookToFields } from './codec'
import { combineBooks, combineRows } from './twin'
import type { Book, SyncRow } from './model'

function book(id: string, over: Partial<Book> = {}): Book {
  return {
    id, isbn: null, title: 'Dune', authors: 'Frank Herbert', publisher: null, publishedYear: null, pageCount: null,
    coverUrl: null, description: null, status: 'TO_READ', rating: null, notes: '', dateAdded: 100, dateRead: null,
    dateStarted: null, currentPage: null, progressPercent: null, review: '', tags: [], series: null,
    seriesNumber: null, lentTo: null, lentOn: null, deleted: false, ...over,
  }
}

function row(b: Book, time: number, over: Record<string, number> = {}): SyncRow {
  const fields = bookToFields(b)
  const times = Object.fromEntries(Object.keys(fields).map((f) => [f, time]))
  return { table: 'books', id: b.id, fields, times: { ...times, ...over } }
}

describe('combineRows', () => {
  it('newestFieldWins', () => {
    const keep = row(book('a', { rating: 3, publisher: 'Old' }), 10, { rating: 50 })
    const other = row(book('b', { rating: 5, publisher: 'New' }), 20)
    const out = combineRows(keep, other)
    expect(out.id).toBe('a')
    expect(out.fields.publisher).toBe('New') // 20 beats 10
    expect(out.fields.rating).toBe(3) // 50 beats 20
    expect(out.times.publisher).toBe(20)
    expect(out.times.rating).toBe(50)
  })

  it('aTieKeepsTheKeptBook', () => {
    const out = combineRows(row(book('a', { publisher: 'Mine' }), 10), row(book('b', { publisher: 'Theirs' }), 10))
    expect(out.fields.publisher).toBe('Mine')
  })

  it('aFilledValueBeatsAnEmptyOneEvenWhenOlder', () => {
    const keep = row(book('a', { publisher: null, rating: 4 }), 100)
    const other = row(book('b', { publisher: 'Ace', rating: null }), 1)
    const out = combineRows(keep, other)
    expect(out.fields.publisher).toBe('Ace')
    expect(out.times.publisher).toBe(1)
    expect(out.fields.rating).toBe(4)
  })

  it('joinsDifferingNotesAndReviewsNewestFirst', () => {
    const keep = row(book('a', { notes: 'older note', review: 'old review' }), 10)
    const other = row(book('b', { notes: 'newer note', review: 'new review' }), 20)
    const out = combineRows(keep, other)
    expect(out.fields.notes).toBe('newer note\n\nolder note')
    expect(out.fields.review).toBe('new review\n\nold review')
    // The joined text is newer than both, so it wins everywhere.
    expect(out.times.notes).toBe(21)
  })

  it('doesNotJoinWhenOneContainsTheOther', () => {
    const keep = row(book('a', { notes: 'read it twice' }), 10)
    const other = row(book('b', { notes: 'read it twice, loved it' }), 5)
    expect(combineRows(keep, other).fields.notes).toBe('read it twice, loved it')
    expect(combineRows(other, keep).fields.notes).toBe('read it twice, loved it')
    expect(combineRows(keep, row(book('c', { notes: 'read it twice' }), 1)).fields.notes).toBe('read it twice')
  })

  it('unionsTagsAndKeepsTheEarliestDateAdded', () => {
    const keep = row(book('a', { tags: ['Classics', 'gift'], dateAdded: 300 }), 10)
    const other = row(book('b', { tags: ['classics', 'sci-fi'], dateAdded: 200 }), 20)
    const out = combineRows(keep, other)
    expect(out.fields.tags).toBe('Classics\ngift\nsci-fi')
    expect(out.fields.date_added).toBe(200)
  })

  it('aMissingTimeCountsAsTheOldest', () => {
    const keep: SyncRow = { table: 'books', id: 'a', fields: { publisher: 'Mine', deleted: false }, times: {} }
    const other: SyncRow = { table: 'books', id: 'b', fields: { publisher: 'Theirs', deleted: false }, times: { publisher: 0 } }
    expect(combineRows(keep, other).fields.publisher).toBe('Theirs')
  })
})

describe('limits', () => {
  it('joiningTwoLongReviewsStaysWithinTheLimit', () => {
    const older = 'a'.repeat(1500)
    const newer = 'b'.repeat(1200)
    const out = combineRows(row(book('a', { review: older }), 10), row(book('b', { review: newer }), 20))
    // Both together would not fit: the newest text stays whole and the older one is dropped.
    expect(out.fields.review).toBe(newer)
    expect(out.times.review).toBe(20)
    const notes = combineRows(
      row(book('a', { notes: 'x'.repeat(15_000) }), 30), row(book('b', { notes: 'y'.repeat(9000) }), 10),
    )
    expect(notes.fields.notes).toBe('x'.repeat(15_000))
    // Joined texts that fit are still joined.
    const fits = combineRows(row(book('a', { review: 'one' }), 10), row(book('b', { review: 'two' }), 20))
    expect(fits.fields.review).toBe('two\n\none')
    // Tags are never joined past their limit either.
    const tag = (c: string) => Array.from({ length: 150 }, (_, i) => `${c}${String(i).padStart(10, '0')}`)
    const tags = combineRows(row(book('a', { tags: tag('a') }), 10), row(book('b', { tags: tag('b') }), 20))
    expect((tags.fields.tags as string).length).toBeLessThanOrEqual(2000)
  })
})

describe('combineBooks', () => {
  it('keepsTheKeptIdAndFillsGapsFromTheOther', () => {
    const keep = book('a', { rating: 4, notes: 'mine' })
    const other = book('b', { publisher: 'Ace', pageCount: 688, notes: 'theirs', tags: ['x'] })
    const out = combineBooks(keep, other)
    expect(out).toMatchObject({ id: 'a', rating: 4, publisher: 'Ace', pageCount: 688, tags: ['x'] })
    // Without times a tie keeps the kept book's text first.
    expect(out.notes).toBe('mine\n\ntheirs')
  })

  it('usesTheFieldTimesItIsGiven', () => {
    const keep = book('a', { publisher: 'Old' })
    const other = book('b', { publisher: 'New' })
    expect(combineBooks(keep, other, { other: { publisher: 9 }, keep: { publisher: 3 } }).publisher).toBe('New')
    expect(combineBooks(keep, other, { other: { publisher: 3 }, keep: { publisher: 9 } }).publisher).toBe('Old')
  })
})
