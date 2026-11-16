/** The date input's text for a time (the local day), and back: the day's start in local time. */
export function dayText(ms: number | null): string {
  if (ms === null) return ''
  const d = new Date(ms)
  const p = (n: number, w = 2) => String(n).padStart(w, '0')
  return `${p(d.getFullYear(), 4)}-${p(d.getMonth() + 1)}-${p(d.getDate())}`
}

export function parseDay(text: string): number | null | undefined {
  if (text.trim() === '') return null
  const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(text.trim())
  if (!m) return undefined
  const ms = new Date(Number(m[1]), Number(m[2]) - 1, Number(m[3])).getTime()
  return Number.isNaN(ms) ? undefined : ms
}

/** A whole number from a box, null when empty, undefined when it isn't one (the box goes back to what was saved). */
export function parseWhole(text: string): number | null | undefined {
  if (text.trim() === '') return null
  const n = Number(text)
  return Number.isInteger(n) && n >= 0 ? n : undefined
}
