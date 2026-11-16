import { QUOTES, type BookQuote } from './quotes'

const DAY_MS = 86_400_000

/** The whole local days since 1970-01-01, so the number changes at local midnight. */
export function dayNumber(date: Date): number {
  return Math.floor(Date.UTC(date.getFullYear(), date.getMonth(), date.getDate()) / DAY_MS)
}

/** The quote for a day: the same for everyone that day. */
export function quoteOfTheDay(date: Date = new Date()): BookQuote {
  return QUOTES[dayNumber(date) % QUOTES.length]
}

/** What the portrait lookup needs of `fetch`. */
export type PortraitFetch = (url: string) => Promise<{ ok: boolean; json: () => Promise<unknown> }>

const CACHE_PREFIX = 'mybooks.portrait.'
const CACHE_MS = 30 * DAY_MS

function readCache(wiki: string, now: number): string | null {
  try {
    const raw: unknown = JSON.parse(window.localStorage.getItem(CACHE_PREFIX + wiki) ?? 'null')
    if (typeof raw !== 'object' || raw === null) return null
    const { url, at } = raw as { url?: unknown; at?: unknown }
    return typeof url === 'string' && typeof at === 'number' && now - at < CACHE_MS && now >= at ? url : null
  } catch {
    return null
  }
}

function writeCache(wiki: string, url: string, now: number) {
  try {
    window.localStorage.setItem(CACHE_PREFIX + wiki, JSON.stringify({ url, at: now }))
  } catch {
    // Not kept this time; it is asked for again next time.
  }
}

/**
 * The Wikipedia lead image of an author (`wiki` is language:Title), a larger size than the summary's; kept for 30 days.
 * Null on any failure: the quote shows without a portrait.
 */
export async function portraitUrl(
  wiki: string,
  fetchImpl: PortraitFetch = (url) => fetch(url),
  now: number = Date.now(),
): Promise<string | null> {
  const cached = readCache(wiki, now)
  if (cached !== null) return cached
  try {
    const colon = wiki.indexOf(':')
    const lang = wiki.slice(0, colon)
    const title = wiki.slice(colon + 1)
    const res = await fetchImpl(`https://${lang}.wikipedia.org/api/rest_v1/page/summary/${encodeURIComponent(title)}`)
    if (!res.ok) return null
    const body = (await res.json()) as { thumbnail?: { source?: unknown } } | null
    const source = body?.thumbnail?.source
    if (typeof source !== 'string' || !/^https:\/\//.test(source)) return null
    const url = source.replace('/330px-', '/640px-')
    writeCache(wiki, url, now)
    return url
  } catch {
    return null
  }
}

/** The author's Wikipedia article, which is also where the portrait is credited. */
export function articleUrl(wiki: string): string {
  const colon = wiki.indexOf(':')
  return `https://${wiki.slice(0, colon)}.wikipedia.org/wiki/${encodeURIComponent(wiki.slice(colon + 1))}`
}
