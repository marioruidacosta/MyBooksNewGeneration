// @vitest-environment node
import { createHash } from 'node:crypto'
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
const f = (fn: (url: string) => Promise<Response>) => fn as unknown as F
const noSleep = () => Promise.resolve()

describe('buildNews', () => {
  it('dedupesByUrlThenTitle', async () => {
    const fetchFn = vi.fn((url: string) =>
      url.includes('a.test')
        ? respond(rss([
            { title: 'Same story', url: 'https://x.test/p?utm=1#top', date: NOW - 1000 },
            { title: 'A rather long identical headline', url: 'https://x.test/q', date: NOW - 2000 },
            { title: 'Short', url: 'https://x.test/s1', date: NOW - 2500 },
          ]))
        : respond(rss([
            { title: 'Same story again', url: 'https://x.test/p', date: NOW - 3000 },
            { title: 'a rather long identical headline ', url: 'https://y.test/z', date: NOW - 4000 },
            { title: 'short', url: 'https://y.test/s2', date: NOW - 4500 },
          ])),
    )
    const out = await buildNews({ feeds: [feed('a'), feed('b')], fetch: fetchFn as unknown as F, now: NOW, previous: null })
    // the same URL is one story; titles are only compared inside one source, and only when longer than 15 characters
    expect(out.file.items.map((i) => i.title)).toEqual(['Same story', 'A rather long identical headline', 'Short', 'a rather long identical headline', 'short'])
    const dup = await buildNews({
      feeds: [feed('a')],
      fetch: f(() => respond(rss([
        { title: 'A rather long identical headline', url: 'https://x.test/1', date: NOW - 1000 },
        { title: 'a rather long identical headline', url: 'https://x.test/2', date: NOW - 2000 },
        { title: 'Short', url: 'https://x.test/3', date: NOW - 3000 },
        { title: 'short', url: 'https://x.test/4', date: NOW - 4000 },
      ]))),
      now: NOW,
      previous: null,
    })
    expect(dup.file.items.map((i) => i.url)).toEqual(['https://x.test/1', 'https://x.test/3', 'https://x.test/4'])
  })

  it('keepsThirtyDaysNewestFirstAtMost120PerLanguage', async () => {
    const many = Array.from({ length: 250 }, (_, i) => ({ title: `T${i}`, url: `https://m.test/${i}`, date: NOW - i * 1000 }))
    const stale = { title: 'Stale', url: 'https://m.test/stale', date: NOW - 31 * DAY }
    const edge = { title: 'Edge', url: 'https://m.test/edge', date: NOW - 29 * DAY }
    const run = (xml: string) => buildNews({ feeds: [feed('m')], fetch: (() => respond(xml)) as unknown as F, now: NOW, previous: null })
    const items = (await run(rss([stale, ...many, edge]))).file.items
    expect(items).toHaveLength(120)
    expect(items[0].title).toBe('T0')
    for (let i = 1; i < items.length; i++) expect(items[i - 1].published).toBeGreaterThanOrEqual(items[i].published)
    expect(items.some((i) => i.title === 'Stale')).toBe(false)
    expect((await run(rss([stale, edge]))).file.items.map((i) => i.title)).toEqual(['Edge'])
    // a missing date counts as fetch time
    expect((await run(rss([{ title: 'No date', url: 'https://m.test/n' }]))).file.items[0].published).toBe(NOW)
  })

  it('portugueseStoriesAreNotPushedOutByEnglishOnes', async () => {
    const en = Array.from({ length: 150 }, (_, i) => ({ title: `E${i}`, url: `https://en.test/${i}`, date: NOW - 1000 - i * 1000 }))
    const pt = Array.from({ length: 130 }, (_, i) => ({ title: `P${i}`, url: `https://pt.test/${i}`, date: NOW - 10 * DAY - i * 1000 }))
    const fetchFn = f((url) => (url.includes('en.test') ? respond(rss(en)) : respond(rss(pt))))
    const { file } = await buildNews({ feeds: [feed('en', 'en'), feed('pt', 'pt')], fetch: fetchFn, now: NOW, previous: null })
    const count = (lang: string) => file.items.filter((i) => i.lang === lang).length
    // 120 of each: the newer English stories do not squeeze the older Portuguese ones out
    expect(count('en')).toBe(120)
    expect(count('pt')).toBe(120)
    expect(file.items.some((i) => i.title === 'P0')).toBe(true)
    expect(file.items.some((i) => i.title === 'E120')).toBe(false)
    expect(file.items.some((i) => i.title === 'P120')).toBe(false)
    for (let i = 1; i < file.items.length; i++) expect(file.items[i - 1].published).toBeGreaterThanOrEqual(file.items[i].published)
  })

  it('oldPictureUrlsInThePreviousFileAreCleaned', async () => {
    const pixel = 'https://media.npr.org/include/images/tracking/npr-rss-pixel.png?story=1'
    const big = 'https://npr.brightspotcdn.com/dims3/default/strip/false/crop/4356x2904+0+0/resize/4356x2904!/quality/85/format/webp/?url=https%3A%2F%2Fx.test%2Fa.jpg'
    const previous: NewsFile = {
      generatedAt: NOW - DAY,
      sources: [],
      items: [old({ source: 'a', url: 'https://a.test/1', image: pixel }), old({ source: 'a', url: 'https://a.test/2', image: big })],
    }
    const { file } = await buildNews({ feeds: [feed('a')], fetch: f(() => respond('', 503)), now: NOW, previous })
    expect(file.items.find((i) => i.url.endsWith('1'))?.image).toBeNull()
    expect(file.items.find((i) => i.url.endsWith('2'))?.image).toContain('/resize/400/quality/85/')
  })

  it('aFailingFeedDoesNotFailTheBuild', async () => {
    const fetchFn = f((url) => {
      if (url.includes('good')) return respond(fixture('sample.atom.xml'))
      if (url.includes('html')) return respond('<!doctype html><html><body>Error</body></html>')
      if (url.includes('down')) return respond('nope', 503)
      return Promise.reject(new Error('socket hang up'))
    })
    const feeds = [feed('good'), feed('html'), feed('down'), feed('boom')]
    const out = await buildNews({ feeds, fetch: fetchFn, now: NOW, previous: null })
    expect(out.file.items).toHaveLength(2)
    expect(out.file.sources.map((s) => [s.id, s.ok])).toEqual([['good', true], ['html', false], ['down', false], ['boom', false]])
    expect(out.log.join('\n')).toMatch(/boom.*socket hang up/)
    expect(out.log).toHaveLength(4)

    // every feed failing keeps the previous items and the previous generatedAt
    const prev: NewsFile = { generatedAt: NOW - DAY, sources: [], items: [old({})] }
    const allDown = await buildNews({ feeds: [feed('down'), feed('old')], fetch: fetchFn, now: NOW, previous: prev })
    expect(allDown.file.items.map((i) => i.title)).toEqual(['Old'])
    expect(allDown.file.sources[0].ok).toBe(false)
    expect(allDown.file.generatedAt).toBe(NOW - DAY)
    expect(out.file.generatedAt).toBe(NOW)
  })

  it('allFeedsFailingWithNoPreviousPublishesAnEmptyList', async () => {
    const fetchFn = f(() => respond('nope', 503))
    const out = await buildNews({ feeds: [feed('a'), feed('b')], fetch: fetchFn, now: NOW, previous: null })
    expect(out.file.items).toEqual([])
    expect(out.file.sources.map((s) => s.ok)).toEqual([false, false])
    expect(out.file.generatedAt).toBe(NOW)
    expect(out.log.some((l) => l.startsWith('WARNING'))).toBe(true)
    // with a previous file there is nothing to warn about
    const prev: NewsFile = { generatedAt: NOW - DAY, sources: [], items: [old({})] }
    expect((await buildNews({ feeds: [feed('a')], fetch: fetchFn, now: NOW, previous: prev })).log.some((l) => l.startsWith('WARNING'))).toBe(false)
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
    const fetchFn = f((url) => url.includes('n.test') ? respond(rss([{ title: 'Dup updated', url: 'https://n.test/1', date: NOW - 1000 }, { title: 'Fresh', url: 'https://n.test/2', date: NOW - 2000 }])) : respond('', 503))
    const out = await buildNews({ feeds: [feed('n'), feed('old')], fetch: fetchFn, now: NOW, previous: prev })
    // a story already in the previous file keeps its earlier date
    expect(out.file.items.map((i) => i.title)).toEqual(['Fresh', 'Dup updated', 'Kept'])
    expect(out.file.generatedAt).toBe(NOW)
  })

  it('anUndatedStoryKeepsItsFirstDate', async () => {
    const prev: NewsFile = { generatedAt: NOW - DAY, sources: [], items: [old({ title: 'Undated', url: 'https://n.test/u', source: 'n', published: NOW - 3 * DAY })] }
    const out = await buildNews({ feeds: [feed('n')], fetch: f(() => respond(rss([{ title: 'Undated', url: 'https://n.test/u' }]))), now: NOW, previous: prev })
    expect(out.file.items).toHaveLength(1)
    expect(out.file.items[0].published).toBe(NOW - 3 * DAY)
  })

  it('aHostilePreviousFileIsCleaned', async () => {
    const url = 'https://old.test/hostile'
    const prev = {
      generatedAt: NOW - DAY,
      sources: [],
      items: [
        { ...old({}), id: 'forged', url, title: `<b>Giant</b> ${'x'.repeat(10000)}`, summary: 'Hello <script>alert(1)</script>there &lt;img src=x onerror=1&gt;', published: NOW + 10 * DAY, image: 'http://old.test/i.jpg', extra: 'x', admin: true },
        { ...old({}), url: 'https://evil.test/1', source: 'unknown' },
        { ...old({}), url: 'https://user:pw@old.test/creds' },
        { ...old({}), url: 'https://old.test/nodate', published: 'yesterday' },
      ],
    } as unknown as NewsFile
    const out = await buildNews({ feeds: [feed('old')], fetch: f(() => respond('', 503)), now: NOW, previous: prev })
    expect(out.file.items).toHaveLength(1)
    const item = out.file.items[0] as unknown as Record<string, unknown>
    expect(Object.keys(item).sort()).toEqual(['id', 'image', 'lang', 'published', 'source', 'summary', 'title', 'url'])
    expect(item.id).toBe(createHash('sha1').update(url).digest('hex'))
    expect((item.title as string).length).toBeLessThanOrEqual(200)
    expect(item.title).not.toMatch(/[<>]/)
    expect(item.summary).toBe('Hello there')
    expect(item.published).toBe(NOW)
    expect(item.image).toBeNull()
  })

  it('aMissingPreviousFileIsNone', async () => {
    expect(await loadPrevious('https://p.test/news.json', f(() => respond('Not found', 404)), noSleep)).toBeNull()
    expect(await loadPrevious(undefined, f(() => respond('{}')), noSleep)).toBeNull()
    const good = { generatedAt: 1, sources: [], items: [old({})] }
    expect((await loadPrevious('https://p.test/news.json', f(() => respond(JSON.stringify(good))), noSleep))?.items).toHaveLength(1)
  })

  it('aPreviousFileServerErrorFailsRatherThanWiping', async () => {
    const calls = vi.fn()
    const bad = (make: () => Promise<Response>) => f(() => { calls(); return make() })
    await expect(loadPrevious('https://p.test/n.json', bad(() => respond('oops', 500)), noSleep)).rejects.toThrow(/previous/i)
    expect(calls).toHaveBeenCalledTimes(3)
    await expect(loadPrevious('https://p.test/n.json', bad(() => Promise.reject(new Error('reset'))), noSleep)).rejects.toThrow(/previous/i)
    await expect(loadPrevious('https://p.test/n.json', bad(() => respond('<html>', 200)), noSleep)).rejects.toThrow(/previous/i)
    await expect(loadPrevious('https://p.test/n.json', bad(() => respond('{"nope":1}', 200)), noSleep)).rejects.toThrow(/previous/i)
    // a blip is retried
    let n = 0
    const flaky = f(() => (++n < 3 ? respond('x', 502) : respond(JSON.stringify({ generatedAt: 1, sources: [], items: [] }))))
    expect((await loadPrevious('https://p.test/n.json', flaky, noSleep))?.items).toEqual([])
  })

  it('anOversizedFeedIsRejected', async () => {
    const MB = 1024 * 1024
    const huge = new Response('<rss/>', { headers: { 'content-length': String(6 * MB) } })
    const chunk = new Uint8Array(MB).fill(32)
    let sent = 0
    const streamed = new Response(new ReadableStream({ pull(c) { if (sent++ < 8) c.enqueue(chunk); else c.close() } }))
    const ok = respond(rss([{ title: 'Fine', url: 'https://ok.test/1', date: NOW - 1000 }]))
    const fetchFn = f((url) => (url.includes('huge') ? Promise.resolve(huge) : url.includes('stream') ? Promise.resolve(streamed) : ok))
    const out = await buildNews({ feeds: [feed('huge'), feed('stream'), feed('ok')], fetch: fetchFn, now: NOW, previous: null })
    expect(out.file.sources.map((s) => s.ok)).toEqual([false, false, true])
    expect(out.log.join('\n')).toMatch(/huge: FAILED.*too large/i)
    expect(out.log.join('\n')).toMatch(/stream: FAILED.*too large/i)
    expect(sent).toBeLessThan(8)
  })
})
