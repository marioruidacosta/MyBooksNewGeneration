import { describe, expect, it } from 'vitest'
import type { Book } from '../sync/model'
import { countText, goalProblem, pace, paceText, percent, readIn, readWithoutDate } from './challenge'

function book(id: string, over: Partial<Book> = {}): Book {
  return {
    id, isbn: null, title: id, authors: '', publisher: null, publishedYear: null, pageCount: null, coverUrl: null,
    description: null, status: 'READ', rating: null, notes: '', dateAdded: 0, dateRead: null, dateStarted: null,
    currentPage: null, progressPercent: null, review: '', tags: [], series: null, seriesNumber: null, lentTo: null,
    lentOn: null, deleted: false, ...over,
  }
}
const day = (y: number, m: number, d: number) => new Date(y, m - 1, d, 12).getTime()

describe('which books count', () => {
  const books = [
    book('old', { dateRead: day(2026, 1, 2) }),
    book('new', { dateRead: day(2026, 11, 2) }),
    book('other', { dateRead: day(2025, 12, 31) }),
    book('reading', { status: 'READING', dateRead: day(2026, 3, 1) }),
    book('undated'),
  ]
  it('readInAYearMostRecentFirst', () => expect(readIn(books, 2026).map((b) => b.id)).toEqual(['new', 'old']))
  it('theLocalNewYearDecides', () => {
    expect(readIn([book('eve', { dateRead: new Date(2026, 0, 1, 0, 30).getTime() })], 2026)).toHaveLength(1)
    expect(readIn([book('eve', { dateRead: new Date(2025, 11, 31, 23, 30).getTime() })], 2026)).toHaveLength(0)
  })
  it('readWithoutADateCountsForNoYear', () => expect(readWithoutDate(books).map((b) => b.id)).toEqual(['undated']))
})

describe('progress', () => {
  it('percentIsCappedAndRoundedDown', () => {
    expect(percent({ year: 2026, goal: 30, read: 18 })).toBe(60)
    expect(percent({ year: 2026, goal: 3, read: 1 })).toBe(33)
    expect(percent({ year: 2026, goal: 3, read: 9 })).toBe(100)
  })
  it('countText', () => {
    expect(countText({ year: 2026, goal: 30, read: 18 })).toBe('18 of 30 books')
    expect(countText({ year: 2026, goal: 1, read: 0 })).toBe('0 of 1 book')
  })
})

describe('pace', () => {
  const today = new Date(2026, 10, 3) // day 307 of 365: 30 books means 25 by now (rounded down)
  it('ahead', () => expect(paceText(pace({ year: 2026, goal: 30, read: 27 }, today))).toBe('2 books ahead of schedule'))
  it('oneBookAhead', () => expect(paceText(pace({ year: 2026, goal: 30, read: 26 }, today))).toBe('1 book ahead of schedule'))
  it('onTrack', () => expect(paceText(pace({ year: 2026, goal: 30, read: 25 }, today))).toBe('On track'))
  it('behind', () => expect(paceText(pace({ year: 2026, goal: 30, read: 20 }, today))).toBe('5 books behind schedule'))
  it('complete', () => expect(paceText(pace({ year: 2026, goal: 30, read: 30 }, today))).toBe('Challenge complete!'))
  it('aPastYearCompleteStaysComplete', () => expect(paceText(pace({ year: 2025, goal: 3, read: 4 }, today))).toBe('Challenge complete!'))
  it('aPastYearEndedShort', () => expect(paceText(pace({ year: 2025, goal: 30, read: 29 }, today))).toBe('Ended 1 book short'))
  it('aFutureYear', () => expect(paceText(pace({ year: 2027, goal: 30, read: 0 }, today))).toBe('Starts on 1 January'))
  it('aLeapYearHas366Days', () => {
    // 31 December 2028 is day 366: the whole goal is due.
    expect(paceText(pace({ year: 2028, goal: 366, read: 365 }, new Date(2028, 11, 31)))).toBe('1 book behind schedule')
  })
})

describe('setting a goal', () => {
  it('accepts', () => expect(goalProblem('2027', ' 24 ', 2026)).toEqual({ year: 2027, goal: 24 }))
  it.each(['1899', '2028', 'abc', '', '2026.5'])('refuses the year %s', (y) => {
    expect(goalProblem(y, '24', 2026)).toEqual({ problem: 'Enter a year up to 2027' })
  })
  it.each(['0', '1000', '-1', 'x', '', '2.5'])('refuses the goal %s', (g) => {
    expect(goalProblem('2026', g, 2026)).toEqual({ problem: 'Enter a number of books from 1 to 999' })
  })
})
