import { describe, expect, it } from 'vitest'
import { newsBase, newsUrls } from './newsClient'

describe('newsClient', () => {
  it('buildsTheUrlsOneLevelAboveTheApp', () => {
    expect(newsBase('/MyBooksNewGeneration/app/', 'https://marioruidacosta.github.io')).toBe(
      'https://marioruidacosta.github.io/MyBooksNewGeneration/news/',
    )
    expect(newsUrls('/MyBooksNewGeneration/app/', 'https://x.io')).toEqual({
      news: 'https://x.io/MyBooksNewGeneration/news/news.json',
      events: 'https://x.io/MyBooksNewGeneration/news/events.json',
      localEvents: '/MyBooksNewGeneration/app/news/events.json',
    })
  })
})
