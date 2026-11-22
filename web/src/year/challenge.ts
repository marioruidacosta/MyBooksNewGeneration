import type { Book } from '../sync/model'

/** The most books a yearly goal can ask for. */
export const MAX_GOAL = 999

/** The earliest year a challenge can be for. */
const FIRST_YEAR = 1900

/** A challenge and the books read toward it so far. */
export type ChallengeProgress = { year: number; goal: number; read: number }

export type Pace =
  | { kind: 'complete' }
  | { kind: 'upcoming' }
  | { kind: 'ended'; short: number }
  | { kind: 'on_track' }
  | { kind: 'ahead'; books: number }
  | { kind: 'behind'; books: number }

/** The books finished in [year] (the reader's own calendar), most recent first. */
export function readIn(books: Book[], year: number): Book[] {
  return books
    .filter((b) => b.status === 'READ' && b.dateRead !== null && new Date(b.dateRead).getFullYear() === year)
    .sort((a, b) => (b.dateRead ?? 0) - (a.dateRead ?? 0))
}

/** Read books without a finish date: they count toward no year. */
export const readWithoutDate = (books: Book[]): Book[] => books.filter((b) => b.status === 'READ' && b.dateRead === null)

export const percent = ({ goal, read }: ChallengeProgress): number =>
  goal <= 0 ? 100 : Math.min(100, Math.floor((read * 100) / goal))

const isLeap = (year: number) => (year % 4 === 0 && year % 100 !== 0) || year % 400 === 0

function dayOfYear(date: Date): number {
  const start = Date.UTC(date.getFullYear(), 0, 1)
  const day = Date.UTC(date.getFullYear(), date.getMonth(), date.getDate())
  return Math.round((day - start) / 86_400_000) + 1
}

/** Where a challenge stands on [today], compared with reading at an even pace through the year. */
export function pace(progress: ChallengeProgress, today: Date): Pace {
  const { year, goal, read } = progress
  if (read >= goal) return { kind: 'complete' }
  if (year > today.getFullYear()) return { kind: 'upcoming' }
  if (year < today.getFullYear()) return { kind: 'ended', short: goal - read }
  const expected = Math.floor((goal * dayOfYear(today)) / (isLeap(year) ? 366 : 365))
  if (read > expected) return { kind: 'ahead', books: read - expected }
  if (read < expected) return { kind: 'behind', books: expected - read }
  return { kind: 'on_track' }
}

const bookCount = (n: number) => (n === 1 ? '1 book' : `${n} books`)

export function paceText(p: Pace): string {
  switch (p.kind) {
    case 'complete':
      return 'Challenge complete!'
    case 'upcoming':
      return 'Starts on 1 January'
    case 'ended':
      return `Ended ${bookCount(p.short)} short`
    case 'on_track':
      return 'On track'
    case 'ahead':
      return `${bookCount(p.books)} ahead of schedule`
    case 'behind':
      return `${bookCount(p.books)} behind schedule`
  }
}

/** "18 of 30 books". */
export const countText = ({ read, goal }: ChallengeProgress): string => `${read} of ${bookCount(goal)}`

/** What was typed as a year and a goal, or what's wrong with it. Years from 1900 to next year can have a goal. */
export function goalProblem(
  yearText: string, goalText: string, thisYear: number,
): { year: number; goal: number } | { problem: string } {
  const whole = (text: string) => (/^\d+$/.test(text.trim()) ? Number(text.trim()) : null)
  const year = whole(yearText)
  const goal = whole(goalText)
  if (year === null || year < FIRST_YEAR || year > thisYear + 1) return { problem: `Enter a year up to ${thisYear + 1}` }
  if (goal === null || goal < 1 || goal > MAX_GOAL) return { problem: `Enter a number of books from 1 to ${MAX_GOAL}` }
  return { year, goal }
}
