import { describe, expect, it, vi } from 'vitest'
import { authorInfo } from './wikipediaAuthor'

const summary = (over: Record<string, unknown> = {}) =>
  new Response(
    JSON.stringify({
      type: 'standard',
      title: 'José Saramago',
      description: 'escritor português',
      extract: 'José de Sousa Saramago foi um escritor português. Recebeu o Nobel em 1998.',
      thumbnail: { source: 'https://upload.wikimedia.org/saramago.jpg' },
      content_urls: { mobile: { page: 'https://pt.m.wikipedia.org/wiki/Jos%C3%A9_Saramago' } },
      ...over,
    }),
    { status: 200 },
  )
const missing = () => new Response('', { status: 404 })

// Each test asks for a different name: answers are kept for the session, as in the app.
describe('the author from Wikipedia', () => {
  it('asksPortugueseWikipediaFirst', async () => {
    const fetchImpl = vi.fn(async (url: string) => (url.startsWith('https://pt.') ? summary() : missing()))
    const info = await authorInfo('José Saramago', fetchImpl as unknown as typeof fetch)
    expect(fetchImpl).toHaveBeenCalledWith('https://pt.wikipedia.org/api/rest_v1/page/summary/Jos%C3%A9_Saramago')
    expect(info).toEqual({
      name: 'José Saramago',
      description: 'escritor português',
      extract: 'José de Sousa Saramago foi um escritor português. Recebeu o Nobel em 1998.',
      photoUrl: 'https://upload.wikimedia.org/saramago.jpg',
      pageUrl: 'https://pt.m.wikipedia.org/wiki/Jos%C3%A9_Saramago',
    })
  })

  it('aNamesakeWhoIsNotAWriterIsLeftOut', async () => {
    const fetchImpl = vi.fn(async (url: string) =>
      url.startsWith('https://pt.')
        ? summary({ title: 'John Williams', description: 'futebolista inglês', extract: 'John Williams é um futebolista.' })
        : summary({ title: 'John Williams (author)', description: 'American novelist', extract: 'John Edward Williams was an American author.' }),
    )
    const info = await authorInfo('John Williams', fetchImpl as unknown as typeof fetch)
    expect(info?.name).toBe('John Williams (author)')
    expect(fetchImpl).toHaveBeenCalledTimes(2)
  })

  it('aDisambiguationPageIsNotABiography', async () => {
    const fetchImpl = vi.fn(async () => summary({ type: 'disambiguation', title: 'Ana Silva', extract: 'Ana Silva pode referir-se a escritora…' }))
    expect(await authorInfo('Ana Silva', fetchImpl as unknown as typeof fetch)).toBeNull()
  })

  it('nothingFoundIsAskedOnlyOnce', async () => {
    const fetchImpl = vi.fn(async () => missing())
    expect(await authorInfo('Nobody Known', fetchImpl as unknown as typeof fetch)).toBeNull()
    expect(await authorInfo(' nobody known ', fetchImpl as unknown as typeof fetch)).toBeNull()
    expect(fetchImpl).toHaveBeenCalledTimes(2) // pt and en, the first time only
  })

  it('noConnectionIsNoBiography', async () => {
    const fetchImpl = vi.fn(async () => {
      throw new TypeError('Failed to fetch')
    })
    expect(await authorInfo('Offline Author', fetchImpl as unknown as typeof fetch)).toBeNull()
    expect(await authorInfo('   ', fetchImpl as unknown as typeof fetch)).toBeNull()
  })
})
