import { mkdir, writeFile } from 'node:fs/promises'
import { join, resolve } from 'node:path'
import { FEEDS, type Feed } from './feeds'
import { httpsUrl, normalizeTitle, normalizeUrl, parseFeed, type NewsItem } from './parse'

export const USER_AGENT = 'MyBooks-news/1.0 (+https://marioruidacosta.github.io/MyBooksNewGeneration/)'
const DAY = 86_400_000
const WINDOW_DAYS = 30
const MAX_ITEMS = 200
const FEED_TIMEOUT_MS = 20_000

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

async function fetchText(fetchFn: typeof fetch, url: string): Promise<string> {
  const ctrl = new AbortController()
  const timer = setTimeout(() => ctrl.abort(), FEED_TIMEOUT_MS)
  try {
    const res = await fetchFn(url, {
      headers: { 'User-Agent': USER_AGENT, Accept: 'application/rss+xml, application/atom+xml, application/xml, text/xml;q=0.9, */*;q=0.5' },
      redirect: 'follow',
      signal: ctrl.signal,
    })
    if (!res.ok) throw new Error(`HTTP ${res.status}`)
    return await res.text()
  } finally {
    clearTimeout(timer)
  }
}

/** The previous published file, or null when there is none (a 404 on the first run, a network error, junk). */
export async function loadPrevious(url: string | undefined, fetchFn: typeof fetch): Promise<NewsFile | null> {
  if (!url) return null
  try {
    const body = JSON.parse(await fetchText(fetchFn, url)) as Partial<NewsFile>
    if (!body || !Array.isArray(body.items)) return null
    return { generatedAt: Number(body.generatedAt) || 0, sources: Array.isArray(body.sources) ? body.sources : [], items: body.items }
  } catch {
    return null
  }
}

function usable(i: unknown): i is NewsItem {
  if (typeof i !== 'object' || i === null) return false
  const x = i as Record<string, unknown>
  return (
    typeof x.title === 'string' && x.title !== '' && typeof x.summary === 'string' && typeof x.source === 'string' &&
    (x.lang === 'pt' || x.lang === 'en') && typeof x.published === 'number' && Number.isFinite(x.published) &&
    httpsUrl(x.url) !== null && (x.image === null || x.image === undefined || httpsUrl(x.image) !== null)
  )
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

  const cutoff = now - WINDOW_DAYS * DAY
  const kept = (opts.previous?.items ?? []).filter(usable).map((i) => ({ ...i, image: i.image ?? null }))
  // New items come first, so a story seen again takes its latest title and date.
  const seenUrls = new Set<string>()
  const seenTitles = new Set<string>()
  const merged: NewsItem[] = []
  for (const item of [...fresh, ...kept]) {
    if (item.published < cutoff) continue
    const u = normalizeUrl(item.url)
    const t = normalizeTitle(item.title)
    if (seenUrls.has(u) || seenTitles.has(t)) continue
    seenUrls.add(u)
    seenTitles.add(t)
    merged.push(item)
  }
  merged.sort((a, b) => b.published - a.published)
  // An outage must never block a deploy: with nothing at all to publish, publish an empty list and say so.
  if (merged.length === 0 && sources.every((s) => !s.ok) && !opts.previous) {
    log.push('WARNING: every feed failed and there is no previous file, so news.json has no items')
  }
  return { file: { generatedAt: now, sources, items: merged.slice(0, MAX_ITEMS) }, log }
}

function arg(name: string): string | undefined {
  const i = process.argv.indexOf(name)
  return i >= 0 ? process.argv[i + 1] : undefined
}

async function main() {
  const out = arg('--out')
  if (!out) throw new Error('usage: build-news.ts --out <dir> [--previous <url>]')
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
