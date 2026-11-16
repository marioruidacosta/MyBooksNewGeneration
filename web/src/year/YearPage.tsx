import { useMemo, useState, useSyncExternalStore } from 'react'
import { CoverImage } from '../covers/CoverImage'
import { plural } from '../library/filters'
import type { LibraryStore } from '../sync/store'
import { MONTHS, yearStats } from './yearStats'

type Props = {
  store: LibraryStore
  /** The year first shown; this year when left out. */
  year?: number
  /** What counts as this year (the next-year button stops there). */
  thisYear?: number
  onOpen: (id: string) => void
  /** Called for a click on the link to the library (a plain link when left out). */
  onLibrary?: () => void
}

// Text is 15 units on a 480-unit chart: about 11px when the chart is as wide as a phone.
const CHART = { width: 480, height: 170, top: 26, bottom: 28 }

/** Books read per month as bars: the values are written on them, and the same numbers are in a table for screen readers. */
function MonthChart({ perMonth, year }: { perMonth: number[]; year: number }) {
  const max = Math.max(1, ...perMonth)
  const slot = CHART.width / 12
  const plotHeight = CHART.height - CHART.top - CHART.bottom
  return (
    <figure className="year-chart">
      <svg viewBox={`0 0 ${String(CHART.width)} ${String(CHART.height)}`} aria-hidden="true" focusable="false">
        {perMonth.map((n, i) => {
          const h = (n / max) * plotHeight
          const x = i * slot + slot * 0.18
          return (
            <g key={MONTHS[i]}>
              <rect className="year-bar" x={x} y={CHART.top + plotHeight - h} width={slot * 0.64} height={h} rx={2} />
              <text className="year-bar-value" x={i * slot + slot / 2} y={CHART.top + plotHeight - h - 5} textAnchor="middle">
                {n}
              </text>
              <text className="year-bar-month" x={i * slot + slot / 2} y={CHART.height - 8} textAnchor="middle">
                {MONTHS[i]}
              </text>
            </g>
          )
        })}
      </svg>
      <table className="visually-hidden">
        <caption>Books read per month in {year}</caption>
        <thead>
          <tr>
            <th scope="col">Month</th>
            <th scope="col">Books</th>
          </tr>
        </thead>
        <tbody>
          {perMonth.map((n, i) => (
            <tr key={MONTHS[i]}>
              <th scope="row">{MONTHS[i]}</th>
              <td>{n}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </figure>
  )
}

function Stars({ score }: { score: number }) {
  const on = Math.floor(score)
  const half = score - on >= 0.5
  const text = Number.isInteger(score) ? String(score) : score.toFixed(1)
  return (
    <span className="year-score">
      <span role="img" aria-label={`${text} out of 5`} className="year-stars">
        {[1, 2, 3, 4, 5].map((n) => (
          <span key={n} className={n <= on ? 'year-star year-star--on' : half && n === on + 1 ? 'year-star year-star--half' : 'year-star'} aria-hidden="true">
            ★
          </span>
        ))}
      </span>
      <span className="year-score__value">{text}</span>
    </span>
  )
}

/** A year of reading: what was finished, against the goal, with pages, months, scores and authors. */
export function YearPage({ store, year: firstYear, thisYear = new Date().getFullYear(), onOpen, onLibrary }: Props) {
  const [year, setYear] = useState(firstYear ?? thisYear)
  const books = useSyncExternalStore(store.subscribe, () => store.books())
  const goal = useSyncExternalStore(store.subscribe, () => store.goal(year))
  const stats = useMemo(() => yearStats(books, year), [books, year])
  const base = import.meta.env.BASE_URL
  const count = stats.read.length
  const hasGoal = goal !== null && goal > 0

  return (
    <main className="year-page">
      <h1>Year in books</h1>
      <div className="year-nav">
        <button type="button" aria-label="Previous year" onClick={() => setYear((y) => y - 1)}>
          ‹
        </button>
        <span className="year-now" aria-live="polite">
          {year}
        </span>
        <button type="button" aria-label="Next year" disabled={year >= thisYear} onClick={() => setYear((y) => y + 1)}>
          ›
        </button>
      </div>

      {count === 0 ? (
        <p className="empty">
          No books finished in {year} yet.{' '}
          <a
            href={base}
            onClick={(e) => {
              if (!onLibrary || e.button !== 0 || e.metaKey || e.ctrlKey || e.shiftKey || e.altKey) return
              e.preventDefault()
              onLibrary()
            }}
          >
            Library
          </a>
        </p>
      ) : (
        <>
          <section className="year-stats" aria-label={`${String(year)} in numbers`}>
            <div className="year-stat">
              <p className="year-stat__big">{hasGoal ? `${String(count)} of ${String(goal)} books` : plural(count, 'book')}</p>
              {hasGoal ? (
                <span
                  className="goal-bar"
                  role="progressbar"
                  aria-label={`${String(year)} goal progress`}
                  aria-valuemin={0}
                  aria-valuemax={goal}
                  aria-valuenow={Math.min(count, goal)}
                >
                  <span style={{ width: `${String(Math.min(100, (count / goal) * 100))}%` }} />
                </span>
              ) : (
                <p className="hint">No goal set for {year}.</p>
              )}
            </div>
            <div className="year-stat">
              <p className="year-stat__big">{stats.pages.toLocaleString('en-GB')} pages</p>
              {stats.withoutPages > 0 && (
                <p className="hint">
                  {stats.withoutPages === 1 ? '1 book has' : `${String(stats.withoutPages)} books have`} no page count
                </p>
              )}
            </div>
            <div className="year-stat">
              <p className="year-stat__big">Average score</p>
              {stats.averageScore === null ? <p className="hint">No book is scored yet.</p> : <Stars score={stats.averageScore} />}
            </div>
          </section>

          <section aria-labelledby="year-months">
            <h2 id="year-months">Books per month</h2>
            <MonthChart perMonth={stats.perMonth} year={year} />
          </section>

          <section aria-labelledby="year-authors">
            <h2 id="year-authors">Top authors</h2>
            <ol className="year-authors" aria-label="Top authors">
              {stats.topAuthors.map((a) => (
                <li key={a.name}>
                  <span>{a.name}</span>
                  <span className="year-authors__count">{plural(a.count, 'book')}</span>
                </li>
              ))}
            </ol>
          </section>

          <section aria-labelledby="year-covers">
            <h2 id="year-covers">The books</h2>
            <ul className="year-covers" aria-label={`Books read in ${String(year)}`}>
              {stats.read.map((b) => (
                <li key={b.id}>
                  <a
                    href={`${base}book/${b.id}`}
                    aria-label={b.title}
                    title={b.title}
                    onClick={(e) => {
                      if (e.button !== 0 || e.metaKey || e.ctrlKey || e.shiftKey || e.altKey) return
                      e.preventDefault()
                      onOpen(b.id)
                    }}
                  >
                    <CoverImage book={b} className="year-cover" />
                  </a>
                </li>
              ))}
            </ul>
          </section>
        </>
      )}
    </main>
  )
}
