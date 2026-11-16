const dayFormat = new Intl.DateTimeFormat('en-GB', { day: 'numeric', month: 'short', year: 'numeric' })

/** A day as the person reads it: "27 Oct 2026" (local time). */
export function formatDay(ms: number): string {
  return dayFormat.format(new Date(ms))
}

/** A day as a date box shows it: "27/10/2026" (local time), empty for none. */
export function dayFieldText(ms: number | null): string {
  if (ms === null) return ''
  const d = new Date(ms)
  const p = (n: number, w = 2) => String(n).padStart(w, '0')
  return `${p(d.getDate())}/${p(d.getMonth() + 1)}/${p(d.getFullYear(), 4)}`
}

/**
 * Reads a typed day, day first and in local time: 27/10/2026, 27-10-2026, 27.10.2026, 27/10/26 (a two-digit year is
 * 20xx) or 2026-10-27. Null for empty input; undefined for anything else, including a day that doesn't exist.
 */
export function parseDayText(text: string): number | null | undefined {
  const t = text.trim()
  if (t === '') return null
  let day: number, month: number, year: number
  const iso = /^(\d{4})-(\d{1,2})-(\d{1,2})$/.exec(t)
  const dmy = /^(\d{1,2})[/.-](\d{1,2})[/.-](\d{4}|\d{2})$/.exec(t)
  if (iso) {
    year = Number(iso[1]); month = Number(iso[2]); day = Number(iso[3])
  } else if (dmy) {
    day = Number(dmy[1]); month = Number(dmy[2]); year = Number(dmy[3])
    if (dmy[3].length === 2) year += 2000
  } else {
    return undefined
  }
  const date = new Date(year, month - 1, day)
  // new Date rolls 31/02 over to March; a real day comes back as itself.
  if (date.getFullYear() !== year || date.getMonth() !== month - 1 || date.getDate() !== day) return undefined
  return date.getTime()
}
