// @vitest-environment node
import { readFileSync } from 'node:fs'
import { describe, expect, it } from 'vitest'
import { MAX_RAW, cleanImage, httpsUrl, parseFeed, summarize, toPlainText } from './parse'

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

  it('rawTextIsCutBeforeStripping', () => {
    // Deterministic stand-in for a timing check: whatever follows the cap is never even looked at.
    expect(MAX_RAW).toBe(20_000)
    const text = toPlainText(`${'a'.repeat(MAX_RAW)}TAIL-MARKER`)
    expect(text).toBe('a'.repeat(MAX_RAW))
    expect(summarize(`${'<'.repeat(MAX_RAW)}after`)).not.toContain('after')
    const xml = `<rss version="2.0"><channel><item><title>Fine</title><link>https://a.test/2</link><description><![CDATA[${'x'.repeat(MAX_RAW)}END]]></description></item></channel></rss>`
    const items = parseFeed(xml, ctx('a', 'en'))
    expect(items.map((i) => i.title)).toEqual(['Fine'])
    expect(items[0].summary).not.toContain('END')
  })

  it('trackingPixelsAreNeverThumbnails', () => {
    const items = parseFeed(fixture('npr.rss.xml'), ctx('npr', 'en'))
    expect(items).toHaveLength(4)
    for (const i of items) expect(i.image ?? '').not.toMatch(/tracking|pixel/)
    // the fourth story has no picture of its own: its only <img> is the tracking pixel
    expect(items[3].image).toBeNull()
    const xml = `<rss version="2.0" xmlns:media="http://search.yahoo.com/mrss/"><channel>
      <item><title>One</title><link>https://a.test/1</link><description><![CDATA[<img src="https://a.test/x/spacer.gif"><img src="https://a.test/a/1x1.png"><img src="https://a.test/b.gif" width="1" height="1"><img src="https://a.test/c.gif" height="2"><img src="https://a.test/real.jpg" width="640"/>]]></description></item>
      <item><title>Two</title><link>https://a.test/2</link><media:content url="https://a.test/beacon/t.gif" medium="image"/><description><![CDATA[<img src="https://a.test/tracking/p.png">]]></description></item>
    </channel></rss>`
    const [one, two] = parseFeed(xml, ctx('a', 'en'))
    expect(one.image).toBe('https://a.test/real.jpg')
    expect(two.image).toBeNull()
  })

  it('theSmallestUsableImageIsChosen', () => {
    const xml = `<rss version="2.0" xmlns:media="http://search.yahoo.com/mrss/"><channel>
      <item><title>Sizes</title><link>https://a.test/1</link>
        <media:content url="https://a.test/1000.jpg" width="1000" medium="image"/>
        <media:content url="https://a.test/60.jpg" width="60" medium="image"/>
        <media:content url="https://a.test/460.jpg" width="460" medium="image"/>
        <media:content url="https://a.test/140.jpg" width="140" medium="image"/>
      </item>
      <item><title>Thumbs</title><link>https://a.test/2</link>
        <media:thumbnail url="https://a.test/t-big.jpg" width="700"/><media:thumbnail url="https://a.test/t-small.jpg" width="300"/>
      </item>
      <item><title>No widths</title><link>https://a.test/3</link>
        <media:content url="https://a.test/first.jpg" medium="image"/><media:content url="https://a.test/second.jpg" medium="image"/>
      </item>
    </channel></rss>`
    const items = parseFeed(xml, ctx('a', 'en'))
    expect(items.map((i) => i.image)).toEqual(['https://a.test/460.jpg', 'https://a.test/t-small.jpg', 'https://a.test/first.jpg'])
  })

  it('oversizedImagesAreDropped', () => {
    const xml = `<rss version="2.0" xmlns:media="http://search.yahoo.com/mrss/"><channel>
      <item><title>Huge only</title><link>https://a.test/1</link><media:content url="https://a.test/huge.jpg" width="1800" medium="image"/></item>
      <item><title>Huge and ok</title><link>https://a.test/2</link>
        <media:content url="https://a.test/huge.jpg" width="1800" medium="image"/><media:content url="https://a.test/ok.jpg" width="470" medium="image"/></item>
      <item><title>Edge</title><link>https://a.test/3</link><media:content url="https://a.test/edge.jpg" width="1200" medium="image"/></item>
    </channel></rss>`
    expect(parseFeed(xml, ctx('a', 'en')).map((i) => i.image)).toEqual([null, 'https://a.test/ok.jpg', 'https://a.test/edge.jpg'])
  })

  it('nprImagesAreResized', () => {
    const [first, , third] = parseFeed(fixture('npr.rss.xml'), ctx('npr', 'en'))
    expect(first.image).toBe(
      'https://npr.brightspotcdn.com/dims3/default/strip/false/crop/4356x2904+0+0/resize/400/quality/85/format/webp/?url=https%3A%2F%2Fnpr-production-npr.s3.us-east-1.amazonaws.com%2Fbrightspot%2F3e%2F55%2F5987a64f424b84b128aba38ba342%2Fnpr-lr-stop-thinking-about-work-harlan-3.jpg',
    )
    expect(third.image).toContain('/resize/400/quality/85/')
    expect(third.image).not.toMatch(/resize\/\d+x\d+/)
    expect(new URL(third.image!).searchParams.get('url')).toMatch(/gettyimages-1959972404-594x594\.jpg$/)
  })

  it('nytImagesUseTheSmallerRendition', () => {
    const big = 'https://static01.nyt.com/images/2026/11/17/multimedia/17TBR-Martin-review-pjgk/17TBR-Martin-review-pjgk-mediumSquareAt3X.jpg'
    const small = big.replace('mediumSquareAt3X', 'mediumThreeByTwo440')
    const rss = `<?xml version="1.0"?><rss version="2.0" xmlns:media="http://search.yahoo.com/mrss/"><channel><title>NYT</title>
      <item><title>A review</title><link>https://www.nytimes.com/2026/11/17/books/review.html</link>
      <pubDate>Mon, 16 Nov 2026 10:00:00 GMT</pubDate><description>Text</description>
      <media:content url="${big}" medium="image" width="1800" height="1800"/></item></channel></rss>`
    expect(parseFeed(rss, ctx('nyt', 'en'))[0].image).toBe(small)
    // A copy already published with the big picture is fixed too; another site's similar name is left alone.
    expect(cleanImage(big)).toBe(small)
    const other = 'https://example.com/a-mediumSquareAt3X.jpg'
    expect(cleanImage(other)).toBe(other)
  })

  it('controlAndBidiCharactersAreRemoved', () => {
    const dirty = 'A\u0000B\u0007C‮D⁦E​F‏G\u007FH\u0085I\uD800J\uDC00K ok'
    expect(toPlainText(dirty)).toBe('ABCDEFGHIJK ok')
    expect(summarize('<p>x‮y</p>')).toBe('xy')
    expect(toPlainText('tab\there\nnew')).toBe('tab here new')
    expect(toPlainText('smile 😀')).toBe('smile 😀')
    expect(toPlainText('&#8238;rtl&#x0;')).toBe('rtl')
  })

  it('urlsWithCredentialsAreRejected', () => {
    expect(httpsUrl('https://user:pw@a.test/x')).toBeNull()
    expect(httpsUrl('https://user@a.test/x')).toBeNull()
    expect(httpsUrl('https://a.test/x')).toBe('https://a.test/x')
  })

  it('rejectsAnHtmlErrorPage', () => {
    expect(() => parseFeed('<!doctype html><html><body>Oops</body></html>', ctx('a', 'en'))).toThrow()
    expect(() => parseFeed('<rss><channel><item><title>x', ctx('a', 'en'))).toThrow()
  })
})
