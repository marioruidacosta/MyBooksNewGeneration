import { render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { QuoteOfTheDay } from './QuoteOfTheDay'
import { dayNumber, portraitUrl, quoteOfTheDay } from './daily'
import { QUOTES } from './quotes'

const summary = (source: string) => ({ ok: true, json: async () => ({ thumbnail: { source } }) })

beforeEach(() => window.localStorage.clear())

describe('quoteOfTheDay', () => {
  it('theQuoteChangesAtLocalMidnight', () => {
    const lastMinute = new Date(2026, 10, 16, 23, 59, 59)
    const firstMinute = new Date(2026, 10, 17, 0, 0, 0)
    expect(dayNumber(firstMinute)).toBe(dayNumber(lastMinute) + 1)
    expect(quoteOfTheDay(lastMinute)).toBe(QUOTES[dayNumber(lastMinute) % QUOTES.length])
    expect(quoteOfTheDay(firstMinute)).toBe(QUOTES[(dayNumber(lastMinute) + 1) % QUOTES.length])
    expect(quoteOfTheDay(firstMinute)).not.toBe(quoteOfTheDay(lastMinute))
  })

  it('theSameDayGivesTheSameQuote', () => {
    expect(quoteOfTheDay(new Date(2026, 10, 16, 0, 0, 1))).toBe(quoteOfTheDay(new Date(2026, 10, 16, 23, 0, 0)))
    expect(dayNumber(new Date(1970, 0, 1, 12))).toBe(0)
  })
})

describe('QuoteOfTheDay', () => {
  it('aFailedPortraitStillShowsTheQuote', async () => {
    const fetchImpl = vi.fn().mockRejectedValue(new Error('offline'))
    const day = new Date(2026, 10, 16, 12)
    const quote = quoteOfTheDay(day)
    const { container } = render(<QuoteOfTheDay now={day} fetchImpl={fetchImpl} />)
    await waitFor(() => expect(fetchImpl).toHaveBeenCalled())
    expect(screen.getByRole('blockquote')).toHaveTextContent(quote.text.split('\n')[0])
    expect(screen.getByRole('link', { name: quote.author })).toHaveAttribute('target', '_blank')
    expect(container.querySelector('img')).toBeNull()
  })

  it('thePortraitUrlIsCached', async () => {
    const fetchImpl = vi.fn().mockResolvedValue(summary('https://upload.wikimedia.org/wikipedia/commons/thumb/a/ab/330px-Jane.jpg'))
    const url = 'https://upload.wikimedia.org/wikipedia/commons/thumb/a/ab/640px-Jane.jpg'
    expect(await portraitUrl('en:Jane_Austen', fetchImpl, 1000)).toBe(url)
    expect(fetchImpl).toHaveBeenCalledWith('https://en.wikipedia.org/api/rest_v1/page/summary/Jane_Austen')
    expect(await portraitUrl('en:Jane_Austen', fetchImpl, 2000)).toBe(url)
    expect(fetchImpl).toHaveBeenCalledTimes(1)
    // After 30 days it asks again.
    expect(await portraitUrl('en:Jane_Austen', fetchImpl, 1000 + 31 * 864e5)).toBe(url)
    expect(fetchImpl).toHaveBeenCalledTimes(2)
    // The title is encoded.
    await portraitUrl('pt:Luís_de_Camões', fetchImpl, 1000)
    expect(fetchImpl).toHaveBeenLastCalledWith('https://pt.wikipedia.org/api/rest_v1/page/summary/Lu%C3%ADs_de_Cam%C3%B5es')
  })

  it('showsThePortraitWhenThereIsOne', async () => {
    const fetchImpl = vi.fn().mockResolvedValue(summary('https://upload.wikimedia.org/x/330px-P.jpg'))
    const { container } = render(<QuoteOfTheDay now={new Date(2026, 10, 16, 12)} fetchImpl={fetchImpl} />)
    await waitFor(() => expect(container.querySelector('img')).not.toBeNull())
    expect(container.querySelector('img')).toHaveAttribute('alt', '')
  })
})
