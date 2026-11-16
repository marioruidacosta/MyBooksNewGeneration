import { describe, expect, it } from 'vitest'
import { restoredUrl } from './restore-path'

const BASE = '/MyBooksNewGeneration/app/'

describe('restoredUrl', () => {
  it('putsTheDeepLinkBack', () => {
    expect(restoredUrl(BASE, `?p=${encodeURIComponent('book/1?x=2#top')}`, '')).toBe(`${BASE}book/1?x=2#top`)
  })

  it('keepsTheHashOfTheRedirect', () => {
    expect(restoredUrl(BASE, '?p=add', '#h')).toBe(`${BASE}add#h`)
  })

  it('doesNothingWithoutP', () => {
    expect(restoredUrl(BASE, '?other=1', '')).toBeNull()
    expect(restoredUrl(BASE, '', '')).toBeNull()
  })

  it('ignoresAnythingThatWouldLeaveTheApp', () => {
    expect(restoredUrl(BASE, `?p=${encodeURIComponent('//evil.example/x')}`, '')).toBeNull()
    expect(restoredUrl(BASE, `?p=${encodeURIComponent('https://evil.example/')}`, '')).toBeNull()
  })

  it('restoresAnEmptyPathToTheAppRoot', () => {
    expect(restoredUrl(BASE, '?p=', '')).toBe(BASE)
  })
})
