import { describe, expect, it } from 'vitest'
import { newsBase, newsUrls, parseEvents, parseNews } from './newsClient'

describe('newsClient', () => {
  it('buildsTheUrlsOneLevelAboveTheApp', () => {
    expect(newsBase('/MyBooksNewGeneration/app/', 'https://marioruidacosta.github.io')).toBe(
      'https://marioruidacosta.github.io/MyBooksNewGeneration/news/',
    )
    expect(newsUrls('/MyBooksNewGeneration/app/', 'https://x.io')).toEqual({
      news: 'https://x.io/MyBooksNewGeneration/news/news.json',
      events: 'https://x.io/MyBooksNewGeneration/news/events.json',
      localEvents: '/MyBooksNewGeneration/app/news-events.json',
    })
  })

  it('readersDropWhatTheAppDrops', () => {
    const item = { id: 'a', title: 'T', summary: '', url: 'https://a.test/1', source: 's', lang: 'en', published: 1, image: null }
    const feed = parseNews({
      items: [item, { ...item, id: 'b', lang: 'fr' }, { ...item, id: 'c', lang: undefined }, { ...item, id: 'd', title: '  ' }, { ...item, id: 'e', lang: 'pt' }],
    })
    expect(feed?.items.map((i) => i.id).sort()).toEqual(['a', 'e'])
    // a file that loaded but holds nothing is an empty list, not a failure
    expect(parseNews({ items: [] })?.items).toEqual([])
    expect(parseNews({ nope: 1 })).toBeNull()
    const ev = { name: 'Fair', place: '', month: 3, kind: 'fair', lang: 'en', url: 'https://f.test' }
    expect(
      parseEvents({ events: [ev, { ...ev, name: 'Plain', url: 'http://f.test' }, { ...ev, name: 'Script', url: 'javascript:alert(1)' }, { ...ev, name: 'None', url: '' }, { ...ev, name: ' ' }] }).map((e) => e.name),
    ).toEqual(['Fair'])
  })
})
