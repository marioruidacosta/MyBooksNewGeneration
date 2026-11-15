import { describe, expect, it } from 'vitest'
import fixtures from '../../test/fixtures/sync-rows.json'
import {
  bookToFields, challengeToFields, quoteToFields, rowToBook, rowToChallenge, rowToQuote,
} from './codec'
import { wireToRow, type SyncRow } from './model'

const rows = fixtures as unknown as SyncRow[]

describe('codec', () => {
  it('codecRoundTrips', () => {
    for (const row of rows) {
      if (row.table === 'books') expect(bookToFields(rowToBook(row))).toEqual(row.fields)
      if (row.table === 'quotes') expect(quoteToFields(rowToQuote(row))).toEqual(row.fields)
      if (row.table === 'challenges') expect(challengeToFields(rowToChallenge(row))).toEqual(row.fields)
    }
  })

  it('a book carries every field', () => {
    const b = rowToBook(rows[0])
    expect(b).toMatchObject({
      id: 'book-uuid', title: 'Les Misérables', publishedYear: 1862, status: 'READ', lentOn: 3000, deleted: false,
    })
  })

  it('tagsJoinWithNewline', () => {
    const b = rowToBook(rows[0])
    expect(b.tags).toEqual(['a', 'b'])
    expect(bookToFields({ ...b, tags: ['x', 'y', 'z'] }).tags).toBe('x\ny\nz')
    expect(bookToFields({ ...b, tags: [] }).tags).toBe('')
  })

  it('a deleted book stays deleted, and the photo marker is kept', () => {
    expect(rowToBook(rows[1]).deleted).toBe(true)
    expect(rowToBook(rows[2]).coverUrl).toBe('photo')
  })

  it('missing values take the app defaults', () => {
    const b = rowToBook({ table: 'books', id: 'n', fields: { status: 'NOPE' }, times: {} })
    expect(b).toMatchObject({ title: '', authors: '', notes: '', review: '', tags: [], status: 'TO_READ', dateAdded: 0, deleted: false })
    expect(rowToChallenge({ table: 'challenges', id: '2025', fields: {}, times: {} })).toEqual({ year: 2025, goal: 0, deleted: false })
  })

  it('the quote and the challenge', () => {
    expect(rowToQuote(rows[3])).toEqual({ id: 'q-uuid', bookId: 'book-uuid', text: 't', page: 9, dateAdded: 5, position: 'p', deleted: false })
    expect(rowToChallenge(rows[4])).toEqual({ year: 2026, goal: 30, deleted: false })
  })

  it('wireToRow folds the server deleted marker into the fields', () => {
    const r = wireToRow({ table: 'challenges', id: '2026', fields: { goal: 3 }, times: { goal: 1 }, deleted: true, seq: 4 })
    expect(r).toEqual({ table: 'challenges', id: '2026', fields: { goal: 3, deleted: true }, times: { goal: 1 }, seq: 4 })
  })
})
