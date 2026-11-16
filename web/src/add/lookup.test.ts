import { describe, expect, it } from 'vitest'
import { createLookup, createLookupApi, normalizeIsbn, type Found, type LookupApi } from './lookup'

const found = (over: Partial<Found> = {}): Found => ({
  isbn: null, title: 'T', authors: ['A'], publisher: null, year: null, pages: null, coverUrl: null, description: null, ...over,
})

const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status })

function fakes(opts: { bnp?: () => Promise<Found | null>; google?: () => Promise<Found[]>; ol?: () => Response }) {
  const calls: string[] = []
  const api: LookupApi = {
    bnp: () => {
      calls.push('bnp')
      return (opts.bnp ?? (() => Promise.resolve(null)))()
    },
    google: () => {
      calls.push('google')
      return (opts.google ?? (() => Promise.resolve([])))()
    },
  }
  const fetchIt = (url: string) => {
    calls.push(url.includes('/authors/') ? 'ol-author' : 'ol')
    return Promise.resolve(url.includes('/authors/') ? json({ name: 'José Saramago' }) : (opts.ol ?? (() => json({}, 404)))())
  }
  return { calls, lookup: createLookup(api, fetchIt) }
}

describe('lookup', () => {
  it('isbnTriesSourcesInOrder', async () => {
    // BNP is down (an error), Open Library has no edition, Google has it, without an ISBN of its own.
    const a = fakes({
      bnp: () => Promise.reject(new Error('502')),
      google: () => Promise.resolve([found({ title: 'Blindness', isbn: null })]),
    })
    const got = await a.lookup.lookupIsbn('9789722015981')
    expect(a.calls).toEqual(['bnp', 'ol', 'google'])
    expect(got).toMatchObject({ title: 'Blindness', isbn: '9789722015981' })

    // The first source that has it ends the chain.
    const b = fakes({ bnp: () => Promise.resolve(found({ title: 'From BNP', isbn: '9789722015981' })) })
    expect((await b.lookup.lookupIsbn('9789722015981'))?.title).toBe('From BNP')
    expect(b.calls).toEqual(['bnp'])

    // Open Library: the edition, then each author.
    const c = fakes({
      ol: () => json({ title: 'Blindness', authors: [{ key: '/authors/OL1A' }], publish_date: 'May 1995', number_of_pages: 300 }),
    })
    const fromOl = await c.lookup.lookupIsbn('9789722015981')
    expect(c.calls).toEqual(['bnp', 'ol', 'ol-author'])
    expect(fromOl).toMatchObject({ title: 'Blindness', authors: ['José Saramago'], year: 1995, pages: 300 })

    // Nobody has it: null. Everybody failed: an error, not a hang.
    expect(await fakes({}).lookup.lookupIsbn('9789722015981')).toBeNull()
    const down = fakes({
      bnp: () => Promise.reject(new Error('timeout')),
      google: () => Promise.reject(new Error('504')),
      ol: () => json({}, 503),
    })
    await expect(down.lookup.lookupIsbn('9789722015981')).rejects.toThrow()
  })

  it('normalizesLikeTheApp', () => {
    expect(normalizeIsbn('978-972-20-1598-1')).toBe('9789722015981')
    expect(normalizeIsbn('0-306-40615-2')).toBe('9780306406157')
    expect(normalizeIsbn('080442957x')).toBe('9780804429573')
    expect(normalizeIsbn('9789722015982')).toBeNull()
    expect(normalizeIsbn('')).toBeNull()
  })

  it('neverKeepsAnEmptyIsbnFromGoogle', async () => {
    const calls: string[] = []
    const api = createLookupApi((path) => {
      calls.push(path)
      return Promise.resolve({
        data: { results: [{ isbn: '', title: 'X', authors: ['A'] }, { isbn: '9789722015981', title: 'Y', authors: [] }] },
        error: null,
      })
    })
    const results = await api.google('x y')
    expect(calls).toEqual(['lookup?source=google&q=x%20y'])
    expect(results.map((r) => r.isbn)).toEqual([null, '9789722015981'])
  })
})
