// @vitest-environment node
import { readFileSync } from 'node:fs'
import { describe, expect, it, vi } from 'vitest'
import { buildNews, loadPrevious, type NewsFile } from './build-news'
import type { Feed } from './feeds'
import type { NewsItem } from './parse'

const fixture = (name: string) => readFileSync(new URL(`./fixtures/${name}`, import.meta.url), 'utf8')
const DAY = 86400000
const NOW = Date.parse('2026-11-16T12:00:00Z')

const feed = (id: string, lang: 'pt' | 'en' = 'en'): Feed => ({ id, name: id.toUpperCase(), lang, url: `https://${id}.test/feed` })
const rss = (items: { title: string; url: string; date?: number }[]) =>
  `<rss version="2.0"><channel>${items
    .map((i) => `<item><title>${i.title}</title><link>${i.url}</link>${i.date ? `<pubDate>${new Date(i.date).toUTCString()}</pubDate>` : ''}</item>`)
    .join('')}</channel></rss>`
const respond = (body: string, status = 200) => Promise.resolve(new Response(body, { status }))
const old = (over: Partial<NewsItem>): NewsItem => ({
  id: 'x', title: 'Old', summary: '', url: 'https://old.test/1', source: 'old', lang: 'en', published: NOW - DAY, image: null, ...over,
})
type F = typeof fetch

describe('buildNews', () => {
  it('dedupesByUrlThenTitle', async () => {
    const fetchFn = vi.fn((url: string) =>
      url.includes('a.test')
        ? respond(rss([
            { title: 'Same story', url: 'https://x.test/p?utm=1#top', date: NOW - 1000 },
            { title: 'Other', url: 'https://x.test/q', date: NOW - 2000 },
          ]))
        : respond(rss([
            { title: 'Same story again', url: 'https://x.test/p', date: NOW - 3000 },
            { title: 'other ', url: 'https://y.test/z', date: NOW - 4000 },
          ])),
    )
    const out = await buildNews({ feeds: [feed('a'), feed('b')], fetch: fetchFn as unknown as F, now: NOW, previous: null })
    expect(out.file.items.map((i) => i.title)).toEqual(['Same story', 'Other'])
  })

  it('keepsThirtyDaysNewestFirstAtMost200', async () => {
    const many = Array.from({ length: 250 }, (_, i) => ({ title: `T${i}`, url: `https://m.test/${i}`, date: NOW - i * 1000 }))
    const stale = { title: 'Stale', url: 'https://m.test/stale', date: NOW - 31 * DAY }
    const edge = { title: 'Edge', url: 'https://m.test/edge', date: NOW - 29 * DAY }
    const run = (xml: string) => buildNews({ feeds: [feed('m')], fetch: (() => respond(xml)) as unknown as F, now: NOW, previous: null })
    const items = (await run(rss([stale, ...many, edge]))).file.items
    expect(items).toHaveLength(200)
    expect(items[0].title).toBe('T0')
    for (let i = 1; i < items.length; i++) expect(items[i - 1].published).toBeGreaterThanOrEqual(items[i].published)
    expect(items.some((i) => i.title === 'Stale')).toBe(false)
    expect((await run(rss([stale, edge]))).file.items.map((i) => i.title)).toEqual(['Edge'])
    // a missing date counts as fetch time
    expect((await run(rss([{ title: 'No date', url: 'https://m.test/n' }]))).file.items[0].published).toBe(NOW)
  })

  it('aFailingFeedDoesNotFailTheBuild', async () => {
    const fetchFn = vi.fn((url: string) => {
      if (url.includes('good')) return respond(fixture('sample.atom.xml'))
      if (url.includes('html')) return respond('<!doctype html><html><body>Error</body></html>')
      if (url.includes('down')) return respond('nope', 503)
      return Promise.reject(new Error('socket hang up'))
    }) as unknown as F
    const feeds = [feed('good'), feed('html'), feed('down'), feed('boom')]
    const out = await buildNews({ feeds, fetch: fetchFn, now: NOW, previous: null })
    expect(out.file.items).toHaveLength(2)
    expect(out.file.sources.map((s) => [s.id, s.ok])).toEqual([['good', true], ['html', false], ['down', false], ['boom', false]])
    expect(out.log.join('\n')).toMatch(/boom.*socket hang up/)
    expect(out.log).toHaveLength(4)

    // every feed failing keeps the previous items
    const prev: NewsFile = { generatedAt: NOW - DAY, sources: [], items: [old({})] }
    const allDown = await buildNews({ feeds: [feed('down')], fetch: fetchFn, now: NOW, previous: prev })
    expect(allDown.file.items.map((i) => i.title)).toEqual(['Old'])
    expect(allDown.file.sources[0].ok).toBe(false)
  })

  it('mergesWithThePreviousFile', async () => {
    const prev: NewsFile = {
      generatedAt: NOW - DAY,
      sources: [],
      items: [
        old({ title: 'Kept', url: 'https://old.test/kept', published: NOW - 5 * DAY }),
        old({ title: 'Too old', url: 'https://old.test/gone', published: NOW - 40 * DAY }),
        old({ title: 'Dup', url: 'https://n.test/1', published: NOW - 2 * DAY }),
        old({ title: 'Http', url: 'http://old.test/http', published: NOW - DAY }),
      ],
    }
    const fetchFn = (() => respond(rss([{ title: 'Dup updated', url: 'https://n.test/1', date: NOW - 1000 }, { title: 'Fresh', url: 'https://n.test/2', date: NOW - 2000 }]))) as unknown as F
    const out = await buildNews({ feeds: [feed('n')], fetch: fetchFn, now: NOW, previous: prev })
    expect(out.file.items.map((i) => i.title)).toEqual(['Dup updated', 'Fresh', 'Kept'])
    expect(out.file.generatedAt).toBe(NOW)
  })

  it('treatsAMissingOrBrokenPreviousFileAsNoPrevious', async () => {
    const f = (fn: () => Promise<Response>) => fn as unknown as F
    expect(await loadPrevious('https://p.test/news.json', f(() => respond('Not found', 404)))).toBeNull()
    expect(await loadPrevious('https://p.test/news.json', f(() => respond('<html>', 200)))).toBeNull()
    expect(await loadPrevious('https://p.test/news.json', f(() => Promise.reject(new Error('x'))))).toBeNull()
    expect(await loadPrevious(undefined, f(() => respond('{}')))).toBeNull()
    const good = { generatedAt: 1, sources: [], items: [old({})] }
    expect((await loadPrevious('https://p.test/news.json', f(() => respond(JSON.stringify(good)))))?.items).toHaveLength(1)
  })
})
