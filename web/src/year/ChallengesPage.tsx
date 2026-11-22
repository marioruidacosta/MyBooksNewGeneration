import { useMemo, useState, useSyncExternalStore, type FormEvent } from 'react'
import { Dialog } from '../community/ui'
import { CoverImage } from '../covers/CoverImage'
import type { LibraryStore } from '../sync/store'
import { countText, goalProblem, pace, paceText, percent, readIn, readWithoutDate, type ChallengeProgress } from './challenge'

type Props = {
  store: LibraryStore
  /** What day it is. */
  today?: () => Date
  onOpen: (id: string) => void
  /** Opens a year's books and figures. */
  onOpenYear: (year: number) => void
}

/** What the goal dialog is doing: a new challenge asks for the year too. */
type Editing = { year: number; goal: number | null; isNew: boolean }

function GoalDialog({
  edit, thisYear, onSave, onRemove, onClose,
}: {
  edit: Editing
  thisYear: number
  onSave: (year: number, goal: number) => void
  onRemove: (() => void) | null
  onClose: () => void
}) {
  const [year, setYear] = useState(String(edit.year))
  const [goal, setGoal] = useState(edit.goal === null ? '' : String(edit.goal))
  const [problem, setProblem] = useState<string | null>(null)
  const title = edit.isNew ? 'New challenge' : edit.goal === null ? `Set ${edit.year} goal` : `Edit ${edit.year} goal`
  const submit = (e: FormEvent) => {
    e.preventDefault()
    const result = goalProblem(year, goal, thisYear)
    if ('problem' in result) setProblem(result.problem)
    else onSave(result.year, result.goal)
  }
  return (
    <Dialog title={title} onClose={onClose}>
      <form onSubmit={submit} noValidate>
        {edit.isNew && (
          <label className="dialog__field">
            Year
            <input inputMode="numeric" value={year} onChange={(e) => setYear(e.target.value)} />
          </label>
        )}
        <label className="dialog__field">
          Books to read
          <input inputMode="numeric" value={goal} onChange={(e) => setGoal(e.target.value)} />
        </label>
        {problem && <p className="error" role="alert">{problem}</p>}
        <div className="dialog__actions">
          {onRemove && <button type="button" className="danger" onClick={onRemove}>Remove</button>}
          <button type="button" className="quiet" onClick={onClose}>Cancel</button>
          <button type="submit">Save</button>
        </div>
      </form>
    </Dialog>
  )
}

/** Yearly reading challenges and the books read toward them, as in the app's "Your reading activity". */
export function ChallengesPage({ store, today = () => new Date(), onOpen, onOpenYear }: Props) {
  const books = useSyncExternalStore(store.subscribe, () => store.books())
  // goals() is a new list each time: compared by its contents.
  const goalsText = useSyncExternalStore(store.subscribe, () => JSON.stringify(store.goals()))
  const goals = useMemo(
    () => (JSON.parse(goalsText) as { year: number; goal: number }[]).filter((g) => g.goal > 0),
    [goalsText],
  )
  const [editing, setEditing] = useState<Editing | null>(null)
  const date = today()
  const thisYear = date.getFullYear()
  const base = import.meta.env.BASE_URL

  const progressOf = (year: number, goal: number): ChallengeProgress => ({ year, goal, read: readIn(books, year).length })
  const goalNow = goals.find((g) => g.year === thisYear)
  const current = goalNow ? progressOf(thisYear, goalNow.goal) : null
  const others = goals.filter((g) => g.year !== thisYear).sort((a, b) => b.year - a.year).map((g) => progressOf(g.year, g.goal))
  const readNow = readIn(books, thisYear)
  const undated = readWithoutDate(books).length

  const save = (year: number, goal: number) => {
    store.edit('challenges', String(year), { goal })
    setEditing(null)
  }

  return (
    <main className="challenges-page">
      <h1>Reading challenges</h1>

      <section className="challenge-card" aria-labelledby="challenge-now">
        <h2 id="challenge-now">{thisYear} Reading Challenge</h2>
        {current ? (
          <>
            <p className="challenge-card__count">{countText(current)}</p>
            <div
              className="goal-bar challenge-card__bar"
              role="progressbar"
              aria-label={`${thisYear} challenge progress`}
              aria-valuemin={0}
              aria-valuemax={100}
              aria-valuenow={percent(current)}
            >
              <span style={{ width: `${percent(current)}%` }} />
            </div>
            <p className="challenge-card__pace">
              <span>{paceText(pace(current, date))}</span>
              <span>{percent(current)}%</span>
            </p>
            <button type="button" aria-label={`Edit ${thisYear} goal`} onClick={() => setEditing({ year: thisYear, goal: current.goal, isNew: false })}>
              Edit goal
            </button>
          </>
        ) : (
          <>
            <p>How many books do you want to read this year?</p>
            <button type="button" className="primary" onClick={() => setEditing({ year: thisYear, goal: null, isNew: false })}>
              Set {thisYear} goal
            </button>
          </>
        )}
      </section>

      <section aria-labelledby="challenge-books">
        <h2 id="challenge-books">Read in {thisYear} · {readNow.length}</h2>
        {readNow.length === 0 ? (
          <p className="community-note">No books finished this year yet.</p>
        ) : (
          <ul className="year-covers" aria-label={`Books read in ${thisYear}`}>
            {readNow.map((b) => (
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
        )}
        {undated > 0 && (
          <p className="community-note">
            {undated === 1 ? '1 read book has no finish date' : `${undated} read books have no finish date`}, so{' '}
            {undated === 1 ? 'it counts' : 'they count'} toward no year.
          </p>
        )}
      </section>

      <section aria-labelledby="challenge-others">
        <h2 id="challenge-others">Challenges</h2>
        {others.length === 0 ? (
          <p className="community-note">Your other years' challenges will be listed here.</p>
        ) : (
          <ul className="challenge-list">
            {others.map((c) => (
              <li key={c.year}>
                <span className="challenge-list__text">
                  <button type="button" className="challenge-list__open" onClick={() => onOpenYear(c.year)}>
                    {c.year} · {countText(c)}
                  </button>
                  <span className="challenge-list__pace">{paceText(pace(c, date))}</span>
                </span>
                <button type="button" className="quiet" aria-label={`Edit ${c.year} goal`} onClick={() => setEditing({ year: c.year, goal: c.goal, isNew: false })}>
                  Edit
                </button>
              </li>
            ))}
          </ul>
        )}
        <button type="button" onClick={() => setEditing({ year: thisYear + 1, goal: null, isNew: true })}>New challenge</button>
      </section>

      {editing && (
        <GoalDialog
          key={`${editing.year}:${String(editing.isNew)}`}
          edit={editing}
          thisYear={thisYear}
          onSave={save}
          onRemove={editing.goal !== null ? () => {
            store.remove('challenges', String(editing.year))
            setEditing(null)
          } : null}
          onClose={() => setEditing(null)}
        />
      )}
    </main>
  )
}
