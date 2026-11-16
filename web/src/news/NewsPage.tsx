import { useEffect, useMemo, useState } from 'react'
import { formatDay } from '../text/dates'
import { isHttps, loadNewsData, type BookEvent, type Fetcher, type NewsData, type NewsItem, type NewsLang } from './newsClient'

type Props = {
  /** How the files are fetched; the browser's fetch unless a test brings its own. */
  fetcher?: Fetcher
  /** What time it is now (ms). */
  now?: () => number
}

type Filter = 'all' | NewsLang

const FILTER_KEY = 'mybooks.news.lang'
const FILTERS: { id: Filter; label: string }[] = [
  { id: 'all', label: 'All' },
  { id: 'pt', label: 'Português' },
  { id: 'en', label: 'English' },
]
const MONTH_NAMES = ['January', 'February', 'March', 'April', 'May', 'June', 'July', 'August', 'September', 'October', 'November', 'December']

const browserFetch: Fetcher = (url) => fetch(url)

function loadFilter(): Filter {
  try {
    const stored = window.localStorage.getItem(FILTER_KEY)
    return stored === 'pt' || stored === 'en' ? stored : 'all'
  } catch {
    return 'all'
  }
}

function saveFilter(filter: Filter): void {
  try {
    window.localStorage.setItem(FILTER_KEY, filter)
  } catch {
    // The choice just isn't kept.
  }
}

const pad = (n: number) => String(n).padStart(2, '0')

function updatedText(ms: number): string {
  const d = new Date(ms)
  return `Updated ${formatDay(ms)}, ${pad(d.getHours())}:${pad(d.getMinutes())}`
}

const startOfDay = (ms: number) => {
  const d = new Date(ms)
  return new Date(d.getFullYear(), d.getMonth(), d.getDate()).getTime()
}

function dayLabel(dayStart: number, today: number): string {
  const days = Math.round((today - dayStart) / 86_400_000)
  if (days === 0) return 'Today'
  if (days === 1) return 'Yesterday'
  return formatDay(dayStart)
}

/** Stories (newest first) grouped by local day, in order. */
function groupByDay(items: NewsItem[], now: number): { key: number; label: string; items: NewsItem[] }[] {
  const today = startOfDay(now)
  const groups: { key: number; label: string; items: NewsItem[] }[] = []
  for (const item of items) {
    const key = startOfDay(item.published)
    const last = groups[groups.length - 1]
    if (last && last.key === key) last.items.push(item)
    else groups.push({ key, label: dayLabel(key, today), items: [item] })
  }
  return groups
}

/** Events from this month onward, wrapping round the year. */
function upcoming(events: BookEvent[], now: number): BookEvent[] {
  const month = new Date(now).getMonth() + 1
  const distance = (e: BookEvent) => (e.month - month + 12) % 12
  return events.map((e, i) => ({ e, i })).sort((a, b) => distance(a.e) - distance(b.e) || a.i - b.i).map(({ e }) => e)
}

function Story({ item, sourceName, onImageFailed, showImage }: { item: NewsItem; sourceName: string; onImageFailed: () => void; showImage: boolean }) {
  return (
    <li className="news-story">
      <div className="news-story__text">
        <p className="news-source">{sourceName}</p>
        <h3 className="news-title">
          <a href={item.url} target="_blank" rel="noopener noreferrer">
            {item.title}
          </a>
        </h3>
        {item.summary !== '' && <p className="news-summary">{item.summary}</p>}
      </div>
      {item.image && showImage && (
        <img className="news-image" src={item.image} alt="" loading="lazy" referrerPolicy="no-referrer" onError={onImageFailed} />
      )}
    </li>
  )
}

function Calendar({ events, now }: { events: BookEvent[]; now: number }) {
  const list = useMemo(() => upcoming(events, now), [events, now])
  if (list.length === 0) return null
  return (
    <aside className="news-calendar" aria-labelledby="news-calendar-title">
      <h2 id="news-calendar-title">Book calendar</h2>
      <ul>
        {list.map((e) => (
          <li key={`${e.name}-${String(e.month)}`}>
            {isHttps(e.url) ? (
              <a href={e.url} target="_blank" rel="noopener noreferrer">
                {e.name}
              </a>
            ) : (
              <span>{e.name}</span>
            )}
            <span className="news-when">
              Usually in {MONTH_NAMES[e.month - 1]}
              {e.place !== '' && `, ${e.place}`}
            </span>
          </li>
        ))}
      </ul>
    </aside>
  )
}

export function NewsPage({ fetcher = browserFetch, now = Date.now }: Props = {}) {
  const [data, setData] = useState<NewsData | null>(null)
  const [attempt, setAttempt] = useState(0)
  const [filter, setFilter] = useState<Filter>(loadFilter)
  const [failedImages, setFailedImages] = useState<ReadonlySet<string>>(new Set())

  useEffect(() => {
    let live = true
    setData(null)
    void loadNewsData(fetcher).then((d) => {
      if (live) setData(d)
    })
    return () => {
      live = false
    }
  }, [fetcher, attempt])

  const current = now()
  const news = data?.news ?? null
  const sourceNames = useMemo(() => new Map((news?.sources ?? []).map((s) => [s.id, s.name])), [news])
  const shown = useMemo(() => (news ? news.items.filter((i) => filter === 'all' || i.lang === filter) : []), [news, filter])
  const groups = useMemo(() => groupByDay(shown, current), [shown, current])

  const choose = (next: Filter) => {
    setFilter(next)
    saveFilter(next)
  }

  return (
    <main className="news-page">
      <header className="news-head">
        <h1>Book news</h1>
        {news && news.generatedAt > 0 && <p className="hint">{updatedText(news.generatedAt)}</p>}
      </header>

      {data === null && (
        <div className="news-skeleton" role="status" aria-label="Loading the news">
          {[0, 1, 2, 3].map((i) => (
            <div key={i} className="news-skeleton__line" />
          ))}
        </div>
      )}

      {data !== null && (
        <div className="news-layout">
          <div className="news-main">
            {news === null ? (
              <div className="news-failed">
                <p className="empty">Couldn't load the news. Try again later.</p>
                <button type="button" onClick={() => setAttempt((n) => n + 1)}>
                  Retry
                </button>
              </div>
            ) : (
              <>
                <div className="chips news-filters" role="group" aria-label="Language">
                  {FILTERS.map((f) => (
                    <button key={f.id} type="button" className="chip" aria-pressed={filter === f.id} onClick={() => choose(f.id)}>
                      {f.label}
                    </button>
                  ))}
                </div>
                {groups.length === 0 && <p className="empty">No stories in this language yet.</p>}
                {groups.map((g) => (
                  <section key={g.key} className="news-day" aria-labelledby={`news-day-${String(g.key)}`}>
                    <h2 id={`news-day-${String(g.key)}`}>{g.label}</h2>
                    <ul className="news-list">
                      {g.items.map((item) => (
                        <Story
                          key={item.id}
                          item={item}
                          sourceName={sourceNames.get(item.source) ?? item.source}
                          showImage={!failedImages.has(item.id)}
                          onImageFailed={() => setFailedImages((prev) => new Set(prev).add(item.id))}
                        />
                      ))}
                    </ul>
                  </section>
                ))}
              </>
            )}
          </div>
          <Calendar events={data.events} now={current} />
        </div>
      )}
    </main>
  )
}
