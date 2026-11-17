import { createHash } from 'node:crypto'
import { mkdir, writeFile } from 'node:fs/promises'
import { join, resolve } from 'node:path'
import { FEEDS, type Feed } from './feeds'
import { cleanTitle, httpsUrl, normalizeTitle, normalizeUrl, parseFeed, summarize, type NewsItem } from './parse'

export const USER_AGENT = 'MyBooks-news/1.0 (+https://marioruidacosta.github.io/MyBooksNewGeneration/)'
const DAY = 86_400_000
const WINDOW_DAYS = 30
const MAX_ITEMS = 200
const FEED_TIMEOUT_MS = 20_000
const MAX_BODY_BYTES = 5 * 1024 * 1024
const PREVIOUS_ATTEMPTS = 3

export interface NewsSource {
  id: string
  name: string
  lang: 'pt' | 'en'
  ok: boolean
}

export interface NewsFile {
  generatedAt: number
  sources: NewsSource[]
  items: NewsItem[]
}

export interface BuildOptions {
  feeds: Feed[]
  fetch: typeof fetch
  now: number
  previous: NewsFile | null
}

class HttpError extends Error {
  constructor(readonly status: number) {
    super(`HTTP ${status}`)
  }
}

/** The body as text, refusing anything over MAX_BODY_BYTES (checked up front and while streaming). */
async function readLimited(res: Response): Promise<string> {
  const declared = Number(res.headers.get('content-length'))
  if (declared > MAX_BODY_BYTES) throw new Error(`response too large (${declared} bytes)`)
  if (!res.body) {
    const text = await res.text()
    if (text.length > MAX_BODY_BYTES) throw new Error('response too large')
    return text
  }
  const reader = res.body.getReader()
  const chunks: Uint8Array[] = []
  let total = 0
  for (;;) {
    const { done, value } = await reader.read()
    if (done) break
    total += value.byteLength
    if (total > MAX_BODY_BYTES) {
      await reader.cancel().catch(() => undefined)
      throw new Error('response too large (over 5 MB)')
    }
    chunks.push(value)
  }
  return new TextDecoder('utf-8').decode(Buffer.concat(chunks))
}

async function fetchText(fetchFn: typeof fetch, url: string): Promise<string> {
  const ctrl = new AbortController()
  const timer = setTimeout(() => ctrl.abort(), FEED_TIMEOUT_MS)
  try {
    const res = await fetchFn(url, {
      headers: { 'User-Agent': USER_AGENT, Accept: 'application/rss+xml, application/atom+xml, application/xml, text/xml;q=0.9, */*;q=0.5' },
      redirect: 'follow',
      signal: ctrl.signal,
    })
    if (!res.ok) throw new HttpError(res.status)
    return await readLimited(res)
  } finally {
    clearTimeout(timer)
  }
}

const sleepFor = (ms: number) => new Promise<void>((r) => setTimeout(r, ms))

/**
 * The previous published file. Only a 404 means there is none (null). Any other problem (network error, 5xx,
 * timeout, a body that is not our JSON) is retried twice and then thrown: the job stops and the old deploy stays,
 * rather than overwriting 30 days of news with a short list.
 */
export async function loadPrevious(
  url: string | undefined,
  fetchFn: typeof fetch,
  sleep: (ms: number) => Promise<void> = sleepFor,
): Promise<NewsFile | null> {
  if (!url) return null
  let last: unknown
  for (let attempt = 1; attempt <= PREVIOUS_ATTEMPTS; attempt++) {
    try {
      const body = JSON.parse(await fetchText(fetchFn, url)) as Partial<NewsFile> | null
      if (!body || !Array.isArray(body.items)) throw new Error('not a news file')
      return { generatedAt: Number(body.generatedAt) || 0, sources: Array.isArray(body.sources) ? body.sources : [], items: body.items }
    } catch (e) {
      if (e instanceof HttpError && e.status === 404) return null
      last = e
      if (attempt < PREVIOUS_ATTEMPTS) await sleep(1000 * attempt)
    }
  }
  throw new Error(`could not load the previous file ${url}: ${last instanceof Error ? last.message : String(last)}`)
}

/** Rebuilds an item of the previous file field by field, so nothing hostile or malformed survives. Null drops it. */
function rebuildPrevious(raw: unknown, now: number, feeds: Map<string, Feed>): NewsItem | null {
  if (typeof raw !== 'object' || raw === null) return null
  const x = raw as Record<string, unknown>
  const feed = typeof x.source === 'string' ? feeds.get(x.source) : undefined
  const url = httpsUrl(x.url)
  if (!feed || !url || typeof x.title !== 'string' || typeof x.published !== 'number' || !Number.isFinite(x.published)) return null
  const title = cleanTitle(x.title)
  if (!title) return null
  return {
    id: createHash('sha1').update(url).digest('hex'),
    title,
    summary: typeof x.summary === 'string' ? summarize(x.summary) : '',
    url,
    source: feed.id,
    lang: feed.lang,
    published: Math.min(x.published, now),
    image: httpsUrl(x.image),
  }
}

export async function buildNews(opts: BuildOptions): Promise<{ file: NewsFile; log: string[] }> {
  const { feeds, now } = opts
  const log: string[] = []
  const fresh: NewsItem[] = []
  const sources: NewsSource[] = []

  const results = await Promise.all(
    feeds.map(async (feed) => {
      try {
        const xml = await fetchText(opts.fetch, feed.url)
        let items = parseFeed(xml, { source: feed.id, lang: feed.lang, now })
        if (feed.filter) items = items.filter((i) => feed.filter!(i.title, i.summary))
        return { feed, items, error: null as string | null }
      } catch (e) {
        const err = e as Error
        const cause = (err.cause as Error | undefined)?.message
        return { feed, items: [] as NewsItem[], error: cause ? `${err.message} (${cause})` : err.message }
      }
    }),
  )
  for (const r of results) {
    sources.push({ id: r.feed.id, name: r.feed.name, lang: r.feed.lang, ok: r.error === null })
    log.push(r.error === null ? `${r.feed.id}: ${r.items.length} items` : `${r.feed.id}: FAILED ${r.error}`)
    fresh.push(...r.items)
  }

  const byId = new Map(feeds.map((fd) => [fd.id, fd]))
  const kept = (opts.previous?.items ?? []).map((i) => rebuildPrevious(i, now, byId)).filter((i): i is NewsItem => i !== null)
  // A story already in the previous file keeps its earlier date, so an undated one does not become "Today" every run.
  const firstSeen = new Map(kept.map((i) => [normalizeUrl(i.url), i.published]))
  for (const item of fresh) {
    const before = firstSeen.get(normalizeUrl(item.url))
    if (before !== undefined) item.published = Math.min(item.published, before)
  }

  const cutoff = now - WINDOW_DAYS * DAY
  // New items come first, so a story seen again takes its latest title.
  const seenUrls = new Set<string>()
  const seenTitles = new Set<string>()
  const merged: NewsItem[] = []
  for (const item of [...fresh, ...kept]) {
    if (item.published < cutoff) continue
    const u = normalizeUrl(item.url)
    const t = normalizeTitle(item.title)
    // The same headline twice inside one source is one story; short titles ("News") are not compared at all.
    const titleKey = t.length > 15 ? `${item.source}|${t}` : null
    if (seenUrls.has(u) || (titleKey && seenTitles.has(titleKey))) continue
    seenUrls.add(u)
    if (titleKey) seenTitles.add(titleKey)
    merged.push(item)
  }
  merged.sort((a, b) => b.published - a.published)
  // An outage must never block a deploy: with nothing at all to publish, publish an empty list and say so.
  if (merged.length === 0 && sources.every((s) => !s.ok) && !opts.previous) {
    log.push('WARNING: every feed failed and there is no previous file, so news.json has no items')
  }
  // generatedAt moves only when something was actually fetched.
  const generatedAt = sources.some((s) => s.ok) ? now : opts.previous?.generatedAt || now
  return { file: { generatedAt, sources, items: merged.slice(0, MAX_ITEMS) }, log }
}

function arg(name: string): string | undefined {
  const i = process.argv.indexOf(name)
  return i >= 0 ? process.argv[i + 1] : undefined
}

async function main() {
  const out = arg('--out')
  if (!out) throw new Error('usage: build-news.ts --out <dir> [--previous <url>]')
  // Anything but a 404 for the previous file stops the job: the old deploy stays, rather than 30 days of news being lost.
  const previous = await loadPrevious(arg('--previous'), fetch)
  console.log(previous ? `previous file: ${previous.items.length} items` : 'previous file: none')
  const { file, log } = await buildNews({ feeds: FEEDS, fetch, now: Date.now(), previous })
  for (const line of log) console.log(line)
  await mkdir(resolve(out), { recursive: true })
  await writeFile(join(resolve(out), 'news.json'), JSON.stringify(file))
  console.log(`wrote ${join(out, 'news.json')}: ${file.items.length} items`)
}

if (!process.env.VITEST) {
  main().catch((e) => {
    console.error(e instanceof Error ? e.message : e)
    process.exit(1)
  })
}
