// @vitest-environment node
import { readFileSync } from 'node:fs'
import { describe, expect, it } from 'vitest'
import { parseFeed, summarize } from './parse'

const fixture = (name: string) => readFileSync(new URL(`./fixtures/${name}`, import.meta.url), 'utf8')
const NOW = Date.parse('2026-11-16T12:00:00Z')
const ctx = (source: string, lang: 'pt' | 'en') => ({ source, lang, now: NOW })

describe('parseFeed', () => {
  it('parsesRssItems', () => {
    for (const [file, source, lang] of [
      ['guardian.rss.xml', 'guardian', 'en'],
      ['apel.rss.xml', 'apel', 'pt'],
    ] as const) {
      const items = parseFeed(fixture(file), ctx(source, lang))
      expect(items).toHaveLength(3)
      for (const it of items) {
        expect(it.title.length).toBeGreaterThan(0)
        expect(it.url).toMatch(/^https:\/\//)
        expect(it.id).toMatch(/^[0-9a-f]{40}$/)
        expect(it.source).toBe(source)
        expect(it.lang).toBe(lang)
        expect(Number.isFinite(it.published)).toBe(true)
        expect(it.summary).not.toMatch(/[<>]/)
      }
    }
    const guardian = parseFeed(fixture('guardian.rss.xml'), ctx('guardian', 'en'))
    expect(guardian[0].image).toMatch(/^https:\/\/i\.guim\.co\.uk\//)
    const apel = parseFeed(fixture('apel.rss.xml'), ctx('apel', 'pt'))
    expect(apel[0].title).toContain('Bolonha')
    expect(apel[0].title).not.toContain('&#')
    expect(apel[0].published).toBeGreaterThan(Date.parse('2026-11-01T00:00:00Z'))
  })

  it('parsesAtomEntries', () => {
    const items = parseFeed(fixture('sample.atom.xml'), ctx('sample', 'en'))
    expect(items).toHaveLength(2) // the third entry has an http link and is dropped
    expect(items[0]).toMatchObject({
      title: 'A new novel wins the prize',
      url: 'https://example.org/news/1',
      summary: 'The jury chose it & praised the prose.',
      published: Date.parse('2026-11-15T09:30:00Z'),
      image: 'https://example.org/thumb1.jpg',
    })
    // updated is the date when there is no published; the image comes from the first <img> in the content
    expect(items[1].published).toBe(Date.parse('2026-11-14T08:00:00Z'))
    expect(items[1].image).toBe('https://example.org/inline.png')
    expect(items[1].summary).toBe('Sales are up.')
  })

  it('stripsHtmlAndCapsTheSummary', () => {
    const html = '<p>Hello <b>world</b> &amp; &quot;friends&quot;&#8230;</p><script>alert(1)</script><style>p{}</style>'
    expect(summarize(html)).toBe('Hello world & "friends"…')
    const long = `<p>${'word '.repeat(200)}</p>`
    const s = summarize(long)
    expect(s.length).toBeLessThanOrEqual(280)
    expect(s.endsWith('…')).toBe(true)
    expect(s).not.toMatch(/\s…$/)
    expect(s.slice(0, -1).endsWith('word')).toBe(true)
    // WordPress feeds add a footer that is no summary at all
    expect(summarize('Real text. The post My title appeared first on My Site.')).toBe('Real text.')
    expect(summarize('The post My title appeared first on APEL.')).toBe('')
    const xml = `<rss version="2.0"><channel><item><title>${'T'.repeat(300)}</title><link>https://a.test/x</link><description>&lt;p&gt;Hi &lt;script&gt;x&lt;/script&gt;there&lt;/p&gt;</description></item></channel></rss>`
    const [item] = parseFeed(xml, ctx('a', 'en'))
    expect(item.title.length).toBeLessThanOrEqual(200)
    expect(item.summary).toBe('Hi there')
  })

  it('keepsOnlyHttpsLinksAndImages', () => {
    const xml = `<rss version="2.0" xmlns:media="http://search.yahoo.com/mrss/"><channel>
      <item><title>Plain http</title><link>http://a.test/1</link></item>
      <item><title>Javascript</title><link>javascript:alert(1)</link></item>
      <item><title>Good</title><link>https://a.test/2</link><media:content url="http://a.test/i.jpg" medium="image"/></item>
      <item><title>Good2</title><link>https://a.test/3</link><media:content url="https://a.test/i.jpg" medium="image"/></item>
      <item><title>Enclosure audio</title><link>https://a.test/4</link><enclosure url="https://a.test/a.mp3" type="audio/mpeg"/></item>
    </channel></rss>`
    const items = parseFeed(xml, ctx('a', 'en'))
    expect(items.map((i) => i.title)).toEqual(['Good', 'Good2', 'Enclosure audio'])
    expect(items[0].image).toBeNull()
    expect(items[1].image).toBe('https://a.test/i.jpg')
    expect(items[2].image).toBeNull()
  })

  it('titlesWithEscapedHtmlBecomePlainText', () => {
    const items = parseFeed(fixture('publico.rss.xml'), ctx('publico', 'pt'))
    expect(items).toHaveLength(3)
    expect(items[1].title).toBe('Embaixada do Brasil em Lisboa celebra 70 anos de Grande Sertão: Veredas')
    for (const i of items) expect(i.title).not.toMatch(/[<>]|&[a-z#0-9]+;/i)
    const xml = '<rss version="2.0"><channel><item><title>A &amp;lt;b&amp;gt;bold&amp;lt;/b&amp;gt; &amp;amp; move</title><link>https://a.test/1</link></item></channel></rss>'
    expect(parseFeed(xml, ctx('a', 'pt'))[0].title).toBe('A bold & move')
  })

  it('rejectsAnHtmlErrorPage', () => {
    expect(() => parseFeed('<!doctype html><html><body>Oops</body></html>', ctx('a', 'en'))).toThrow()
    expect(() => parseFeed('<rss><channel><item><title>x', ctx('a', 'en'))).toThrow()
  })
})
