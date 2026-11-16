import { fireEvent, render, screen, waitFor } from '@testing-library/react'
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
    const url = 'https://upload.wikimedia.org/wikipedia/commons/thumb/a/ab/500px-Jane.jpg'
    expect(await portraitUrl('en:Jane_Austen', fetchImpl, 1000)).toBe(url)
    expect(fetchImpl).toHaveBeenCalledWith('https://en.wikipedia.org/api/rest_v1/page/summary/Jane_Austen', { referrerPolicy: 'no-referrer' })
    expect(await portraitUrl('en:Jane_Austen', fetchImpl, 2000)).toBe(url)
    expect(fetchImpl).toHaveBeenCalledTimes(1)
    // After 30 days it asks again.
    expect(await portraitUrl('en:Jane_Austen', fetchImpl, 1000 + 31 * 864e5)).toBe(url)
    expect(fetchImpl).toHaveBeenCalledTimes(2)
    // The title is encoded.
    await portraitUrl('pt:Luís_de_Camões', fetchImpl, 1000)
    expect(fetchImpl).toHaveBeenLastCalledWith('https://pt.wikipedia.org/api/rest_v1/page/summary/Lu%C3%ADs_de_Cam%C3%B5es', {
      referrerPolicy: 'no-referrer',
    })
  })

  it('aPortraitImageThatFailsIsNotRetriedForADay', async () => {
    const fetchImpl = vi.fn().mockResolvedValue(summary('https://upload.wikimedia.org/x/330px-P.jpg'))
    const day = new Date(2026, 10, 16, 12)
    const first = render(<QuoteOfTheDay now={day} fetchImpl={fetchImpl} />)
    await waitFor(() => expect(first.container.querySelector('img')).not.toBeNull())
    fireEvent.error(first.container.querySelector('img')!)
    expect(first.container.querySelector('img')).toBeNull()
    // The quote stays.
    expect(screen.getByRole('blockquote')).toBeInTheDocument()
    first.unmount()

    // The next visit asks nobody and shows no picture: the miss is remembered for a day, as a failed lookup is.
    const second = render(<QuoteOfTheDay now={day} fetchImpl={fetchImpl} />)
    await new Promise((r) => setTimeout(r, 20))
    expect(fetchImpl).toHaveBeenCalledTimes(1)
    expect(second.container.querySelector('img')).toBeNull()
    expect(await portraitUrl(quoteOfTheDay(day).wiki, fetchImpl, Date.now() + 2 * 864e5)).not.toBeNull()
    expect(fetchImpl).toHaveBeenCalledTimes(2)
  })

  it('showsThePortraitWhenThereIsOne', async () => {
    const fetchImpl = vi.fn().mockResolvedValue(summary('https://upload.wikimedia.org/x/330px-P.jpg'))
    const { container } = render(<QuoteOfTheDay now={new Date(2026, 10, 16, 12)} fetchImpl={fetchImpl} />)
    await waitFor(() => expect(container.querySelector('img')).not.toBeNull())
    expect(container.querySelector('img')).toHaveAttribute('alt', '')
  })

  it('aFailedLookupIsNotRetriedForADay', async () => {
    const fetchImpl = vi.fn().mockRejectedValue(new Error('offline'))
    expect(await portraitUrl('en:Jane_Austen', fetchImpl, 1000)).toBeNull()
    expect(await portraitUrl('en:Jane_Austen', fetchImpl, 5000)).toBeNull()
    expect(fetchImpl).toHaveBeenCalledTimes(1)
    expect(await portraitUrl('en:Jane_Austen', fetchImpl, 1000 + 2 * 864e5)).toBeNull()
    expect(fetchImpl).toHaveBeenCalledTimes(2)
  })

  it('anOldCachedAddressIsIgnored', async () => {
    window.localStorage.setItem('mybooks.portrait.en:Jane_Austen', JSON.stringify({ url: 'https://x/640px-Old.jpg', at: 1000 }))
    const fetchImpl = vi.fn().mockResolvedValue(summary('https://upload.wikimedia.org/x/330px-P.jpg'))
    expect(await portraitUrl('en:Jane_Austen', fetchImpl, 2000)).toBe('https://upload.wikimedia.org/x/500px-P.jpg')
  })

  it('leavingTheKnownPageAbortsTheLookup', async () => {
    let signal: AbortSignal | undefined
    const fetchImpl = vi.fn((_url: string, init?: { signal?: AbortSignal }) => {
      signal = init?.signal
      return new Promise<never>(() => {})
    })
    const { unmount } = render(<QuoteOfTheDay now={new Date(2026, 10, 16, 12)} fetchImpl={fetchImpl} />)
    await waitFor(() => expect(fetchImpl).toHaveBeenCalled())
    expect(signal?.aborted).toBe(false)
    unmount()
    expect(signal?.aborted).toBe(true)
  })

  it('aNarrowScreenDoesNotDownloadThePortrait', async () => {
    window.matchMedia = ((q: string) => ({ matches: false, media: q, addEventListener() {}, removeEventListener() {} })) as unknown as typeof window.matchMedia
    const fetchImpl = vi.fn().mockResolvedValue(summary('https://upload.wikimedia.org/x/330px-P.jpg'))
    const { container } = render(<QuoteOfTheDay now={new Date(2026, 10, 16, 12)} fetchImpl={fetchImpl} />)
    await new Promise((r) => setTimeout(r, 20))
    expect(container.querySelector('img')).toBeNull()
    expect(screen.getByRole('blockquote')).toBeInTheDocument()
    delete (window as { matchMedia?: unknown }).matchMedia
  })
})
