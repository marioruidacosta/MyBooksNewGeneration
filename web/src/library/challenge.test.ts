import { describe, expect, it } from 'vitest'
import { challengePercent, countText, pace, paceText } from './challenge'

// As the app's ReadingChallenge: 30 books by 3 November (day 307 of 365) means 25 by now (rounded down).
const NOV_3_2026 = new Date(2026, 10, 3)

describe('reading challenge', () => {
  it('paceComparesWithAnEvenPaceThroughTheYear', () => {
    expect(pace(2026, 30, 27, NOV_3_2026)).toEqual({ kind: 'ahead', books: 2 })
    expect(pace(2026, 30, 25, NOV_3_2026)).toEqual({ kind: 'onTrack' })
    expect(pace(2026, 30, 20, NOV_3_2026)).toEqual({ kind: 'behind', books: 5 })
    expect(pace(2026, 30, 30, NOV_3_2026)).toEqual({ kind: 'complete' })
    expect(pace(2027, 30, 0, NOV_3_2026)).toEqual({ kind: 'upcoming' })
    expect(pace(2025, 30, 20, NOV_3_2026)).toEqual({ kind: 'ended', short: 10 })
  })

  it('theWordsAreTheAppsWords', () => {
    expect(paceText({ kind: 'ahead', books: 2 })).toBe('2 books ahead of schedule')
    expect(paceText({ kind: 'behind', books: 1 })).toBe('1 book behind schedule')
    expect(paceText({ kind: 'onTrack' })).toBe('On track')
    expect(paceText({ kind: 'complete' })).toBe('Challenge complete!')
    expect(paceText({ kind: 'upcoming' })).toBe('Starts on 1 January')
    expect(paceText({ kind: 'ended', short: 3 })).toBe('Ended 3 books short')
    expect(countText(22, 25)).toBe('22 of 25 books')
    expect(countText(0, 1)).toBe('0 of 1 book')
  })

  it('percentIsWholeAndStopsAt100', () => {
    expect(challengePercent(22, 25)).toBe(88)
    expect(challengePercent(1, 3)).toBe(33)
    expect(challengePercent(40, 25)).toBe(100)
    expect(challengePercent(0, 0)).toBe(100)
  })
})
