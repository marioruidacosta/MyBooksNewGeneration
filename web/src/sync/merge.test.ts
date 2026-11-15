import { describe, expect, it } from 'vitest'
import { mergeFields, stamp } from './merge'
import type { SyncRow } from './model'

const row = (fields: Record<string, unknown>, times: Record<string, number>): SyncRow => ({
  table: 'books',
  id: 'b1',
  fields,
  times,
})

describe('mergeFields', () => {
  it('newestFieldWins', () => {
    const local = row({ rating: 3 }, { rating: 10 })
    expect(mergeFields(local, row({ rating: 5 }, { rating: 20 })).fields.rating).toBe(5)
    expect(mergeFields(local, row({ rating: 2 }, { rating: 5 })).fields.rating).toBe(3)
  })

  it('equalTimesKeepLocal', () => {
    const merged = mergeFields(row({ rating: 3 }, { rating: 10 }), row({ rating: 9 }, { rating: 10 }))
    expect(merged.fields.rating).toBe(3)
  })

  it('deletionIsAField', () => {
    const local = row({ title: 'A', deleted: false }, { title: 20, deleted: 20 })
    expect(mergeFields(local, row({ deleted: true }, { deleted: 30 })).fields.deleted).toBe(true)

    const deleted = row({ title: 'A', deleted: true }, { title: 20, deleted: 30 })
    const merged = mergeFields(deleted, row({ title: 'B' }, { title: 40 }))
    expect(merged.fields.title).toBe('B')
    expect(merged.fields.deleted).toBe(true)
  })

  it('a missing local time counts as -1, and a remote field without a time is ignored', () => {
    const merged = mergeFields(row({ notes: 'x' }, {}), row({ notes: 'y', review: 'r' }, { notes: 0 }))
    expect(merged.fields.notes).toBe('y')
    expect(merged.fields.review).toBeUndefined()
  })

  it('without a local row the remote one is taken, and the seq follows the remote', () => {
    const remote = { ...row({ a: 1 }, { a: 1 }), seq: 7 }
    expect(mergeFields(undefined, remote)).toEqual(remote)
    expect(mergeFields(row({ a: 0 }, { a: 0 }), remote).seq).toBe(7)
  })

  it('stamp gives every changed field the time', () => {
    expect(stamp(['rating', 'notes'], 99)).toEqual({ rating: 99, notes: 99 })
  })
})
