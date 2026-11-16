export type NewsLang = 'pt' | 'en'

export type NewsSource = { id: string; name: string; lang: NewsLang; ok: boolean }

export type NewsItem = {
  id: string
  title: string
  summary: string
  url: string
  source: string
  lang: NewsLang
  published: number
  image: string | null
}

export type NewsFeed = { generatedAt: number; sources: NewsSource[]; items: NewsItem[] }

export type BookEvent = { name: string; place: string; month: number; kind: string; lang: string; url: string }

export type NewsData = {
  /** Null when the news couldn't be loaded or held nothing to show. */
  news: NewsFeed | null
  /** The book calendar; empty when it couldn't be loaded. */
  events: BookEvent[]
}

/** The part of fetch this page uses, so a test can bring its own. */
export type Fetcher = (url: string) => Promise<Response>

/** True for a link that is safe to show: an absolute https: address. */
export function isHttps(url: unknown): url is string {
  if (typeof url !== 'string') return false
  try {
    return new URL(url).protocol === 'https:'
  } catch {
    return false
  }
}

/** The folder the job publishes into: one level above the app (".../app/" gives ".../news/"). */
export function newsBase(baseUrl: string, origin: string): string {
  return new URL('../news/', new URL(baseUrl, origin)).href
}

export function newsUrls(baseUrl: string, origin: string) {
  const base = newsBase(baseUrl, origin)
  return {
    news: `${base}news.json`,
    events: `${base}events.json`,
    // Vite serves public/news/ inside the app's own folder, which is all there is in dev.
    localEvents: `${baseUrl}news/events.json`,
  }
}

async function getJson(fetcher: Fetcher, url: string): Promise<unknown> {
  const res = await fetcher(url)
  if (!res.ok) throw new Error(`${String(res.status)} for ${url}`)
  return res.json() as Promise<unknown>
}

const isObject = (v: unknown): v is Record<string, unknown> => typeof v === 'object' && v !== null

function parseItem(raw: unknown): NewsItem | null {
  if (!isObject(raw)) return null
  const { id, title, summary, url, source, lang, published, image } = raw
  if (typeof title !== 'string' || title.trim() === '' || !isHttps(url)) return null
  if (typeof published !== 'number' || !Number.isFinite(published)) return null
  return {
    id: typeof id === 'string' ? id : url,
    title,
    summary: typeof summary === 'string' ? summary : '',
    url,
    source: typeof source === 'string' ? source : '',
    lang: lang === 'pt' ? 'pt' : 'en',
    published,
    image: isHttps(image) ? image : null,
  }
}

/** Reads news.json, keeping only stories that can be shown safely; null when nothing is left. */
export function parseNews(raw: unknown): NewsFeed | null {
  if (!isObject(raw) || !Array.isArray(raw.items)) return null
  const items = raw.items.map(parseItem).filter((i): i is NewsItem => i !== null)
  if (items.length === 0) return null
  const sources = Array.isArray(raw.sources)
    ? raw.sources.filter((s): s is NewsSource => isObject(s) && typeof s.id === 'string' && typeof s.name === 'string')
    : []
  const generatedAt = typeof raw.generatedAt === 'number' ? raw.generatedAt : 0
  return { generatedAt, sources, items: items.sort((a, b) => b.published - a.published) }
}

export function parseEvents(raw: unknown): BookEvent[] {
  if (!isObject(raw) || !Array.isArray(raw.events)) return []
  const events: BookEvent[] = []
  for (const e of raw.events) {
    if (!isObject(e) || typeof e.name !== 'string' || typeof e.month !== 'number') continue
    if (!Number.isInteger(e.month) || e.month < 1 || e.month > 12) continue
    events.push({
      name: e.name,
      place: typeof e.place === 'string' ? e.place : '',
      month: e.month,
      kind: typeof e.kind === 'string' ? e.kind : '',
      lang: typeof e.lang === 'string' ? e.lang : '',
      url: typeof e.url === 'string' ? e.url : '',
    })
  }
  return events
}

/** Loads the news and the calendar. Either may fail on its own; nothing here throws. */
export async function loadNewsData(
  fetcher: Fetcher,
  baseUrl: string = import.meta.env.BASE_URL,
  origin: string = window.location.origin,
): Promise<NewsData> {
  const urls = newsUrls(baseUrl, origin)
  const news = getJson(fetcher, urls.news).then(parseNews, () => null)
  const events = getJson(fetcher, urls.events)
    .catch(() => getJson(fetcher, urls.localEvents))
    .then(parseEvents, () => [])
  return { news: await news, events: await events }
}
