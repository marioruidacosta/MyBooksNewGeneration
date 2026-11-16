import { describe, expect, it } from 'vitest'
import { buildNews } from './build-news'
import { FEEDS, keepsBookStory } from './feeds'

describe('feeds', () => {
  it('rtpKeepsOnlyBookStories', () => {
    const rtp = FEEDS.find((f) => f.id === 'rtp')!
    expect(rtp.filter).toBeDefined()
    const keep = (title: string, summary = '') => keepsBookStory(title, summary)
    expect(keep('Novo livro de Lídia Jorge chega às livrarias')).toBe(true)
    expect(keep('A ESCRITORA premiada', '')).toBe(true)
    expect(keep('Concerto', 'A poesia de Sophia lida em palco')).toBe(true)
    expect(keep('Feira do Livro de Lisboa abre')).toBe(true)
    expect(keep('Prémio literário distingue romance')).toBe(true)
    expect(keep('Escritora vence prémio')).toBe(true)
    expect(keep('Obra literária e literatura')).toBe(true)
    expect(keep('Prémio Pessoa entregue hoje')).toBe(false)
    expect(keep('Festival de cinema abre em Cannes', 'Filmes e atores')).toBe(false)
    expect(keep('O delivro não conta')).toBe(false)
    expect(FEEDS.map((f) => f.id)).toEqual(['apel', 'publico', 'expresso', 'observador', 'sapomag', 'jl', 'rtp', 'guardian', 'lithub', 'nyt', 'npr'])
    expect(FEEDS.filter((f) => f.filter).map((f) => f.id)).toEqual(['expresso', 'observador', 'sapomag', 'rtp'])
  })
  it('expressoKeepsOnlyBookStories', () => {
    const expresso = FEEDS.find((f) => f.id === 'expresso')!
    const rtp = FEEDS.find((f) => f.id === 'rtp')!
    expect(expresso.url).toBe('https://feeds.feedburner.com/expresso-geral')
    expect(expresso.filter).toBe(rtp.filter) // one shared filter
    expect(expresso.filter!('Nova edição do romance de Gonçalo M. Tavares', '')).toBe(true)
    expect(expresso.filter!('SpaceX recupera a nave Starship', 'Economia espacial')).toBe(false)
    const publico = FEEDS.find((f) => f.id === 'publico')!
    expect(publico.url).toBe('https://www.publico.pt/api/list/rss/livros')
    expect(publico.filter).toBeUndefined()
  })
  it('observadorAndSapoKeepOnlyBookStories', () => {
    const rtp = FEEDS.find((f) => f.id === 'rtp')!
    const obs = FEEDS.find((f) => f.id === 'observador')!
    const sapo = FEEDS.find((f) => f.id === 'sapomag')!
    expect(obs.url).toBe('https://observador.pt/feed/')
    expect(sapo.url).toBe('https://mag.sapo.pt/rss')
    expect(obs.filter).toBe(rtp.filter)
    expect(sapo.filter).toBe(rtp.filter)
    expect(obs.filter!('Lídia Jorge lança novo romance', '')).toBe(true)
    expect(sapo.filter!('Receitas de outono', 'Seis pratos para o frio')).toBe(false)
  })

  it('theFeedsOwnTitleIsNeverAnItem', async () => {
    const xml = '<rss version="2.0"><channel><title>Livros e literatura</title><description>Feed com os últimos livros e artigos</description><link>https://c.test/</link><item><title>Eleições</title><link>https://c.test/1</link><description>Política</description></item><item><title>Novo livro de poesia</title><link>https://c.test/2</link></item></channel></rss>'
    const feed = { id: 'c', name: 'C', lang: 'pt' as const, url: 'https://c.test/feed', filter: keepsBookStory }
    const out = await buildNews({ feeds: [feed], fetch: (() => Promise.resolve(new Response(xml))) as unknown as typeof fetch, now: Date.now(), previous: null })
    expect(out.file.items.map((i) => i.title)).toEqual(['Novo livro de poesia'])
  })
})
