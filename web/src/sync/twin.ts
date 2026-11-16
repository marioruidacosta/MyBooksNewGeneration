import { bookToFields, rowToBook } from './codec'
import { isOverLimit, limitOf } from './limits'
import { TAG_SEPARATOR, type Book, type SyncRow } from './model'

/** The time (ms) each field of a row last changed, by the server's field name. */
export type FieldTimes = Record<string, number>

/**
 * The one copy of the twin rule: how two copies of the same book become one. Used by import (a file's book and the
 * library's copy) and by merging duplicates.
 * - Per field, the newest value wins (a missing time counts as the oldest; a tie keeps [keep]'s value).
 * - A filled value beats an empty one, whatever their times.
 * - Notes and review that differ are joined with a blank line, the newer first, unless one already contains the
 *   other (then the longer stays). The joined text is timed after both, so it wins everywhere.
 * - A join that would pass the server's length limit is not made: the newest text stays whole, the older is dropped.
 * - Tags are joined (a tag in both is kept once, ignoring capitals; a tag that would pass the limit is left out), and
 *   the earliest date added stays.
 * - The id, the table and `deleted` are [keep]'s.
 */
export function combineRows(keep: SyncRow, other: SyncRow): SyncRow {
  const fields: Record<string, unknown> = {}
  const times: FieldTimes = {}
  const names = new Set([...Object.keys(keep.fields), ...Object.keys(other.fields)])
  for (const f of names) {
    const inKeep = f in keep.fields
    const inOther = f in other.fields
    const kv = keep.fields[f]
    const ov = other.fields[f]
    const kt = keep.times[f] ?? -1
    const ot = other.times[f] ?? -1
    const takeKeep = () => {
      fields[f] = kv
      if (f in keep.times) times[f] = kt
    }
    const takeOther = () => {
      fields[f] = ov
      if (f in other.times) times[f] = ot
    }
    if (f === 'deleted' || !inOther) {
      if (inKeep) takeKeep()
    } else if (!inKeep) {
      takeOther()
    } else if (f === 'tags') {
      fields[f] = joinTags(kv, ov)
      times[f] = Math.max(kt, ot)
    } else if (f === 'date_added') {
      const k = typeof kv === 'number' && kv > 0
      const o = typeof ov === 'number' && ov > 0
      if (k && o) {
        if ((ov as number) < (kv as number)) takeOther()
        else takeKeep()
      } else if (o) takeOther()
      else takeKeep()
    } else if (isEmpty(kv)) {
      if (isEmpty(ov)) takeKeep()
      else takeOther()
    } else if (isEmpty(ov)) {
      takeKeep()
    } else if ((f === 'notes' || f === 'review') && typeof kv === 'string' && typeof ov === 'string' && kv !== ov) {
      if (kv.includes(ov)) takeKeep()
      else if (ov.includes(kv)) takeOther()
      else {
        const joined = ot > kt ? `${ov}\n\n${kv}` : `${kv}\n\n${ov}`
        if (isOverLimit(f, joined)) {
          // Both would not fit (the server refuses the whole row): the newest text stays whole, the older is dropped.
          if (ot > kt) takeOther()
          else takeKeep()
        } else {
          fields[f] = joined
          times[f] = Math.max(kt, ot) + 1
        }
      }
    } else if (ot > kt) {
      takeOther()
    } else {
      takeKeep()
    }
  }
  return { table: keep.table, id: keep.id, fields, times, seq: keep.seq }
}

/** [combineRows] for books. [times] are the books' field times, by server field name (none: every field ties). */
export function combineBooks(
  keep: Book,
  other: Book,
  times: { keep?: FieldTimes; other?: FieldTimes } = {},
): Book {
  const row = (b: Book, t: FieldTimes | undefined): SyncRow => ({
    table: 'books', id: b.id, fields: bookToFields(b), times: { ...t },
  })
  return rowToBook(combineRows(row(keep, times.keep), row(other, times.other)))
}

const isEmpty = (v: unknown): boolean => v === null || v === undefined || v === ''

function joinTags(a: unknown, b: unknown): string {
  const seen = new Set<string>()
  const out: string[] = []
  const max = limitOf('tags') ?? Infinity
  let length = 0
  for (const v of [a, b]) {
    for (const t of typeof v === 'string' ? v.split(TAG_SEPARATOR) : []) {
      if (t.length > 0 && !seen.has(t.toLowerCase())) {
        const next = length + (out.length > 0 ? TAG_SEPARATOR.length : 0) + t.length
        if (next > max) continue
        length = next
        seen.add(t.toLowerCase())
        out.push(t)
      }
    }
  }
  return out.join(TAG_SEPARATOR)
}
