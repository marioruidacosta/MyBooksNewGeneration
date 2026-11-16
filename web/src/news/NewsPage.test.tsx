import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import fixture from './fixtures/news.json'
import { NewsPage } from './NewsPage'
import type { Fetcher } from './newsClient'

// 17 Nov 2026, midday, local time: the fixture's stories are given dates around it, so the test doesn't depend on the time zone.
const NOW = new Date(2026, 10, 17, 12, 0).getTime()
const at = (daysAgo: number, hour: number) => new Date(2026, 10, 17 - daysAgo, hour, 0).getTime()

const stories = fixture.items.map((item, i) => ({ ...item, published: [at(0, 9), at(0, 8), at(1, 20), at(1, 10), at(5, 12)][i] }))
const newsJson = { generatedAt: at(0, 11) + 5 * 60_000, sources: fixture.sources, items: stories }

const eventsJson = {
  events: [
    { name: "Correntes d'Escritas", place: 'Póvoa de Varzim', month: 2, kind: 'festival', lang: 'pt', url: 'https://www.cm-pvarzim.pt/correntes-d-escritas' },
    { name: 'Booker Prize', place: 'London', month: 11, kind: 'prize', lang: 'en', url: 'https://thebookerprizes.com' },
    { name: 'Frankfurter Buchmesse', place: 'Frankfurt', month: 10, kind: 'fair', lang: 'en', url: 'https://www.buchmesse.de/en' },
    { name: 'Odd link', place: 'Nowhere', month: 12, kind: 'fair', lang: 'en', url: 'javascript:alert(1)' },
  ],
}

const json = (body: unknown) => Promise.resolve(new Response(JSON.stringify(body), { status: 200 }))
const working: Fetcher = (url) => (url.endsWith('events.json') ? json(eventsJson) : json(newsJson))

function renderPage(fetcher: Fetcher = working) {
  return render(<NewsPage fetcher={fetcher} now={() => NOW} />)
}

beforeEach(() => {
  window.localStorage.clear()
})

describe('NewsPage', () => {
  it('showsStoriesGroupedByDay', async () => {
    renderPage()
    expect(await screen.findByRole('heading', { name: 'Today' })).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Yesterday' })).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: '12 Nov 2026' })).toBeInTheDocument()
    expect(screen.getByRole('heading', { level: 1, name: 'Book news' })).toBeInTheDocument()
    expect(screen.getByText(/^Updated 17 Nov 2026, 11:05$/)).toBeInTheDocument()

    const today = screen.getByRole('heading', { name: 'Today' }).closest('section') as HTMLElement
    expect(within(today).getAllByRole('listitem')).toHaveLength(2)
    // The source name comes from the sources list, not the id.
    expect(within(today).getByText('The New York Times Books')).toBeInTheDocument()
    expect(within(today).getByRole('link', { name: stories[0].title })).toBeInTheDocument()
    expect(within(today).getByText(stories[0].summary)).toBeInTheDocument()
  })

  it('showsTheTitleOnlyWhenThereIsNoSummary', async () => {
    renderPage()
    const apel = stories.find((s) => s.source === 'apel')!
    const row = (await screen.findByRole('link', { name: apel.title })).closest('li') as HTMLElement
    expect(row.querySelector('.news-summary')).toBeNull()
  })

  it('filtersByLanguageAndRemembersIt', async () => {
    const user = userEvent.setup()
    const first = renderPage()
    await screen.findByRole('heading', { name: 'Today' })
    const pt = stories.filter((s) => s.lang === 'pt')
    const en = stories.filter((s) => s.lang === 'en')
    expect(pt.length).toBeGreaterThan(0)
    expect(en.length).toBeGreaterThan(0)
    expect(screen.getByRole('button', { name: 'All' })).toHaveAttribute('aria-pressed', 'true')

    await user.click(screen.getByRole('button', { name: 'Português' }))
    for (const s of pt) expect(screen.getByRole('link', { name: s.title })).toBeInTheDocument()
    for (const s of en) expect(screen.queryByRole('link', { name: s.title })).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Português' })).toHaveAttribute('aria-pressed', 'true')

    first.unmount()
    renderPage()
    await screen.findByRole('link', { name: pt[0].title })
    expect(screen.getByRole('button', { name: 'Português' })).toHaveAttribute('aria-pressed', 'true')
    expect(screen.queryByRole('link', { name: en[0].title })).not.toBeInTheDocument()
  })

  it('calendarStartsAtThisMonth', async () => {
    renderPage()
    const calendar = await screen.findByRole('complementary', { name: 'Book calendar' })
    const names = within(calendar).getAllByRole('listitem').map((li) => li.textContent)
    // November now: Booker (11), Odd link (12), then Correntes (2), then Frankfurt (10) after wrapping round the year.
    expect(names[0]).toBe('Booker PrizeUsually in November, London')
    expect(names[1]).toBe('Odd linkUsually in December, Nowhere')
    expect(names[2]).toContain('Correntes')
    expect(names[3]).toContain('Frankfurter Buchmesse')
    expect(within(calendar).getByRole('link', { name: 'Booker Prize' })).toHaveAttribute('href', 'https://thebookerprizes.com')
    expect(within(calendar).getByText('Usually in February, Póvoa de Varzim')).toBeInTheDocument()
    expect(calendar.textContent).not.toContain('·')
    // A link that isn't https is never rendered as a link.
    expect(within(calendar).queryByRole('link', { name: 'Odd link' })).not.toBeInTheDocument()
  })

  it('aFailedLoadSaysSoAndRetries', async () => {
    const user = userEvent.setup()
    let up = false
    const flaky: Fetcher = (url) => (up ? working(url) : Promise.reject(new Error('offline')))
    renderPage(vi.fn(flaky))
    expect(await screen.findByText("Couldn't load the news. Try again later.")).toBeInTheDocument()
    up = true
    await user.click(screen.getByRole('button', { name: 'Retry' }))
    expect(await screen.findByRole('heading', { name: 'Today' })).toBeInTheDocument()
    expect(screen.queryByText("Couldn't load the news. Try again later.")).not.toBeInTheDocument()
  })

  it('aFailedLoadKeepsTheCalendar', async () => {
    const eventsOnly: Fetcher = (url) => (url.endsWith('events.json') ? json(eventsJson) : Promise.resolve(new Response('', { status: 404 })))
    renderPage(eventsOnly)
    expect(await screen.findByText("Couldn't load the news. Try again later.")).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Booker Prize' })).toBeInTheDocument()
  })

  it('storyLinksOpenInANewTab', async () => {
    const hostile = {
      ...newsJson,
      items: [
        ...stories,
        { ...stories[0], id: 'x1', title: 'Script story', url: 'javascript:alert(1)' },
        { ...stories[0], id: 'x2', title: 'Plain http story', url: 'http://example.com/a' },
      ],
    }
    renderPage((url) => (url.endsWith('events.json') ? json(eventsJson) : json(hostile)))
    const link = await screen.findByRole('link', { name: stories[0].title })
    expect(link).toHaveAttribute('href', stories[0].url)
    expect(link).toHaveAttribute('target', '_blank')
    expect(link).toHaveAttribute('rel', 'noopener noreferrer')
    for (const a of screen.getAllByRole('link')) {
      expect(a.getAttribute('href')).toMatch(/^https:\/\//)
      expect(a).toHaveAttribute('rel', 'noopener noreferrer')
    }
    expect(screen.queryByText('Script story')).not.toBeInTheDocument()
    expect(screen.queryByText('Plain http story')).not.toBeInTheDocument()
  })

  it('imagesAreLazyPrivateAndHiddenWhenTheyFail', async () => {
    renderPage()
    await screen.findByRole('heading', { name: 'Today' })
    const withImage = stories.find((s) => s.image)!
    const img = document.querySelector(`img[src="${withImage.image}"]`) as HTMLImageElement
    expect(img).toHaveAttribute('loading', 'lazy')
    expect(img).toHaveAttribute('referrerpolicy', 'no-referrer')
    expect(img).toHaveAttribute('alt', '')
    img.dispatchEvent(new Event('error'))
    await vi.waitFor(() => expect(document.querySelector(`img[src="${withImage.image}"]`)).toBeNull())
  })
})
