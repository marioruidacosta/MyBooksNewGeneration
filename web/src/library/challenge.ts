/** Where a year's reading challenge stands, as the app's ReadingChallenge works it out. */
export type Pace =
  | { kind: 'complete' }
  /** A future year. */
  | { kind: 'upcoming' }
  /** A past year that wasn't completed, [short] books short. */
  | { kind: 'ended'; short: number }
  | { kind: 'onTrack' }
  | { kind: 'ahead'; books: number }
  | { kind: 'behind'; books: number }

const bookCount = (n: number) => (n === 1 ? '1 book' : `${String(n)} books`)

/** Whole per cent of the goal, at most 100; a goal of 0 is already done. */
export const challengePercent = (read: number, goal: number) => (goal <= 0 ? 100 : Math.min(100, Math.floor((read * 100) / goal)))

/** "22 of 25 books". */
export const countText = (read: number, goal: number) => `${String(read)} of ${bookCount(goal)}`

const isLeap = (y: number) => (y % 4 === 0 && y % 100 !== 0) || y % 400 === 0

/** The day of the year of [d] on the calendar where it is seen: 1 January is day 1. */
function dayOfYear(d: Date): number {
  return Math.round((Date.UTC(d.getFullYear(), d.getMonth(), d.getDate()) - Date.UTC(d.getFullYear(), 0, 1)) / 86_400_000) + 1
}

/**
 * Compared with reading at an even pace through the year: 30 books by 3 November (day 307 of 365) means 25 by now
 * (rounded down), so 27 read is 2 ahead.
 */
export function pace(year: number, goal: number, read: number, today: Date): Pace {
  if (read >= goal) return { kind: 'complete' }
  if (year > today.getFullYear()) return { kind: 'upcoming' }
  if (year < today.getFullYear()) return { kind: 'ended', short: goal - read }
  const expected = Math.floor((goal * dayOfYear(today)) / (isLeap(year) ? 366 : 365))
  const difference = read - expected
  if (difference > 0) return { kind: 'ahead', books: difference }
  if (difference < 0) return { kind: 'behind', books: -difference }
  return { kind: 'onTrack' }
}

/** "2 books ahead of schedule", "Challenge complete!", … */
export function paceText(p: Pace): string {
  switch (p.kind) {
    case 'complete':
      return 'Challenge complete!'
    case 'upcoming':
      return 'Starts on 1 January'
    case 'ended':
      return `Ended ${bookCount(p.short)} short`
    case 'onTrack':
      return 'On track'
    case 'ahead':
      return `${bookCount(p.books)} ahead of schedule`
    case 'behind':
      return `${bookCount(p.books)} behind schedule`
  }
}
