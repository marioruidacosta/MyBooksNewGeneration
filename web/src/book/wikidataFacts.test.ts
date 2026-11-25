import { describe, expect, it, vi } from 'vitest'
import { bookFacts, factRows } from './wikidataFacts'

const item = (id: string, labels: Record<string, string>, claims: Record<string, unknown[]> = {}) => ({
  id,
  labels: Object.fromEntries(Object.entries(labels).map(([language, value]) => [language, { language, value }])),
  claims: Object.fromEntries(
    Object.entries(claims).map(([p, values]) => [p, values.map((value) => ({ mainsnak: { datavalue: { value } } }))]),
  ),
})
const ref = (id: string) => ({ 'entity-type': 'item', id })

// Blindness as Wikidata has it, next to the film of the same name.
const ENTITIES: Record<string, ReturnType<typeof item>> = {
  Q838405: item('Q838405', { pt: 'Ensaio sobre a Cegueira', en: 'Blindness' }, { P57: [ref('Q310343')] }),
  Q826428: item('Q826428', { pt: 'Ensaio sobre a Cegueira', en: 'Blindness' }, {
    P50: [ref('Q37060')],
    P577: [{ time: '+1995-00-00T00:00:00Z', precision: 9 }],
    P407: [ref('Q5146')],
    P166: [ref('Q130553019'), ref('Q999')],
    P136: [ref('Q944250'), ref('Q24925')],
    P155: [ref('Q1351463')],
    P156: [ref('Q1216446')],
  }),
  Q37060: item('Q37060', { pt: 'José Saramago', en: 'José Saramago' }),
  Q130553019: item('Q130553019', {}),
  Q999: item('Q999', { en: 'Some Prize' }),
  Q944250: item('Q944250', { pt: 'ficção científica social', en: 'social science fiction' }),
  Q24925: item('Q24925', { pt: 'ficção científica', en: 'science fiction' }),
  Q1351463: item('Q1351463', { pt: 'O Evangelho segundo Jesus Cristo', en: 'The Gospel According to Jesus Christ' }),
  Q1216446: item('Q1216446', { pt: 'Ensaio sobre a Lucidez', en: 'Seeing' }),
  Q5146: item('Q5146', { pt: 'português', en: 'Portuguese' }),
}

function wikidata(search: string[] = ['Q838405', 'Q826428']) {
  return vi.fn(async (url: string) => {
    const q = new URL(url).searchParams
    expect(q.get('origin')).toBe('*')
    if (q.get('action') === 'wbsearchentities') return Response.json({ search: search.map((id) => ({ id })) })
    const ids = (q.get('ids') ?? '').split('|')
    return Response.json({ entities: Object.fromEntries(ids.map((id) => [id, ENTITIES[id] ?? { id, missing: '' }])) })
  })
}

describe('the book from Wikidata', () => {
  it('findsTheWorkByItsTitleAndAuthor', async () => {
    const fetchImpl = wikidata()
    const facts = await bookFacts('Blindness', 'José Saramago', fetchImpl as unknown as typeof fetch)
    expect(facts).toEqual({
      firstPublished: '1995',
      originalTitle: 'Ensaio sobre a Cegueira',
      originalLanguage: 'português',
      genres: ['ficção científica social', 'ficção científica'],
      awards: ['Some Prize'],
      follows: 'O Evangelho segundo Jesus Cristo',
      followedBy: 'Ensaio sobre a Lucidez',
      pageUrl: 'https://www.wikidata.org/wiki/Q826428',
    })
  })

  it('aWorkBySomeoneElseIsNotThisBook', async () => {
    const fetchImpl = wikidata()
    expect(await bookFacts('Blindness', 'Somebody Else', fetchImpl as unknown as typeof fetch)).toBeNull()
  })

  it('isAskedOnlyOncePerBook', async () => {
    const fetchImpl = wikidata()
    await bookFacts('Ensaio sobre a Cegueira', 'José Saramago', fetchImpl as unknown as typeof fetch)
    const calls = fetchImpl.mock.calls.length
    await bookFacts('ensaio sobre a cegueira', 'José Saramago', fetchImpl as unknown as typeof fetch)
    expect(fetchImpl.mock.calls.length).toBe(calls)
  })

  it('noConnectionIsNoFacts', async () => {
    const fetchImpl = vi.fn(async () => {
      throw new TypeError('Failed to fetch')
    })
    expect(await bookFacts('Offline Book', 'An Author', fetchImpl as unknown as typeof fetch)).toBeNull()
    expect(await bookFacts('No Author Book', '', fetchImpl as unknown as typeof fetch)).toBeNull()
  })

  it('theRowsLeaveOutWhatTheBookAlreadySays', () => {
    const facts = {
      firstPublished: '1995', originalTitle: 'Ensaio sobre a Cegueira', originalLanguage: 'português', genres: ['a', 'b', 'c', 'd'],
      awards: [], follows: null, followedBy: 'Seeing', pageUrl: 'https://www.wikidata.org/wiki/Q1',
    }
    expect(factRows(facts, 'Blindness')).toEqual([
      ['First published', '1995'],
      ['Original title', 'Ensaio sobre a Cegueira (português)'],
      ['Genre', 'a, b, c'],
      ['Followed by', 'Seeing'],
    ])
    // The book is already the original.
    expect(factRows(facts, 'Ensaio Sobre a Cegueira').map(([label]) => label)).not.toContain('Original title')
  })
})
