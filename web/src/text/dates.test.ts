import { describe, expect, it } from 'vitest'
import { formatDay, parseDayText } from './dates'

describe('dates', () => {
  it('formatDayShowsDayMonthYear', () => {
    expect(formatDay(new Date(2026, 9, 27).getTime())).toBe('27 Oct 2026')
    expect(formatDay(new Date(2026, 2, 3, 23, 59).getTime())).toBe('3 Mar 2026')
  })

  it('parseDayTextAcceptsDayFirst', () => {
    const day = new Date(2026, 9, 27).getTime()
    for (const text of ['27/10/2026', '27-10-2026', '27.10.2026', '2026-10-27', '27/10/26', ' 27/10/2026 ']) {
      expect(parseDayText(text), text).toBe(day)
    }
    expect(parseDayText('3/4/2026')).toBe(new Date(2026, 3, 3).getTime())
    expect(parseDayText('')).toBeNull()
    expect(parseDayText('   ')).toBeNull()
    for (const text of ['10/27/2026', '31/02/2026', 'yesterday', '27/10', '2026/10/27', '0/1/2026']) {
      expect(parseDayText(text), text).toBeUndefined()
    }
  })
})
