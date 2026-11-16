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
export type PortraitFetch = (
  url: string,
  init?: { signal?: AbortSignal; referrerPolicy?: 'no-referrer' },
) => Promise<{ ok: boolean; json: () => Promise<unknown> }>

// v2: the first version kept 640px addresses, which Wikimedia does not serve.
const CACHE_PREFIX = 'mybooks.portrait.v2.'
const CACHE_MS = 30 * DAY_MS
/** How long "no portrait" is remembered, so a failure is not asked again on every visit. */
const MISS_MS = DAY_MS

/** The remembered address, null for a remembered miss, undefined when nothing (fresh) is remembered. */
function readCache(wiki: string, now: number): string | null | undefined {
  try {
    const raw: unknown = JSON.parse(window.localStorage.getItem(CACHE_PREFIX + wiki) ?? 'null')
    if (typeof raw !== 'object' || raw === null) return undefined
    const { url, at } = raw as { url?: unknown; at?: unknown }
    if (typeof at !== 'number' || now < at) return undefined
    if (url === null) return now - at < MISS_MS ? null : undefined
    return typeof url === 'string' && now - at < CACHE_MS ? url : undefined
  } catch {
    return undefined
  }
}

function writeCache(wiki: string, url: string | null, now: number) {
  try {
    window.localStorage.setItem(CACHE_PREFIX + wiki, JSON.stringify({ url, at: now }))
  } catch {
    // Not kept this time; it is asked for again next time.
  }
}

/** Remembers that this portrait's picture would not load, so it is not asked for again until the miss expires (a day). */
export function missPortrait(wiki: string, now: number = Date.now()): void {
  writeCache(wiki, null, now)
}

/**
 * The Wikipedia lead image of an author (`wiki` is language:Title) at 500px, a width Wikimedia serves; kept for 30
 * days, and a miss for one day. Null on any failure: the quote shows without a portrait.
 */
export async function portraitUrl(
  wiki: string,
  fetchImpl: PortraitFetch = (url, init) => fetch(url, init),
  now: number = Date.now(),
  signal?: AbortSignal,
): Promise<string | null> {
  const cached = readCache(wiki, now)
  if (cached !== undefined) return cached
  try {
    const colon = wiki.indexOf(':')
    const lang = wiki.slice(0, colon)
    const title = wiki.slice(colon + 1)
    const address = `https://${lang}.wikipedia.org/api/rest_v1/page/summary/${encodeURIComponent(title)}`
    // No referrer, as for the picture itself: the page's address is nobody else's business.
    const res = await fetchImpl(address, signal ? { signal, referrerPolicy: 'no-referrer' } : { referrerPolicy: 'no-referrer' })
    const body = res.ok ? ((await res.json()) as { thumbnail?: { source?: unknown } } | null) : null
    const source = body?.thumbnail?.source
    if (typeof source !== 'string' || !/^https:\/\//.test(source)) {
      writeCache(wiki, null, now)
      return null
    }
    const url = source.replace('/330px-', '/500px-')
    writeCache(wiki, url, now)
    return url
  } catch {
    // A lookup that was cancelled says nothing about the portrait.
    if (!signal?.aborted) writeCache(wiki, null, now)
    return null
  }
}

/** The author's Wikipedia article, which is also where the portrait is credited. */
export function articleUrl(wiki: string): string {
  const colon = wiki.indexOf(':')
  return `https://${wiki.slice(0, colon)}.wikipedia.org/wiki/${encodeURIComponent(wiki.slice(colon + 1))}`
}
