import { useRef, useState } from 'react'
import { STATUSES, type Book, type Status } from '../sync/model'
import { STATUS_LABELS } from './filters'

/**
 * A book's status in the table. Chrome and Edge fire `change` on every Up and Down arrow of a focused, closed select,
 * and a status change wipes dates and progress, so arrowing past a value must not commit it. A change is kept as a
 * draft and committed on Enter, on leaving the control, or at once when it follows a pointer press (a pick from the
 * list).
 */
export function StatusSelect({ book, label, onCommit }: { book: Book; label: string; onCommit: (status: Status) => void }) {
  const [draft, setDraft] = useState<Status | null>(null)
  const pointer = useRef(false)
  const commit = (status: Status | null) => {
    setDraft(null)
    if (status && status !== book.status) onCommit(status)
  }
  return (
    <select
      className="status-select"
      aria-label={label}
      value={draft ?? book.status}
      onPointerDown={() => {
        pointer.current = true
      }}
      onMouseDown={() => {
        pointer.current = true
      }}
      onChange={(e) => {
        const next = STATUSES.find((s) => s === e.target.value)
        if (!next) return
        if (pointer.current) {
          pointer.current = false
          commit(next)
        } else {
          setDraft(next)
        }
      }}
      onKeyDown={(e) => {
        if (e.key === 'Enter') commit(draft)
        // From here a change is the keyboard's, not a pick from an open list.
        else pointer.current = false
      }}
      onKeyUp={(e) => {
        if (e.key === 'Enter') commit(draft)
      }}
      onBlur={() => {
        pointer.current = false
        commit(draft)
      }}
    >
      {STATUSES.map((s) => (
        <option key={s} value={s}>
          {STATUS_LABELS[s]}
        </option>
      ))}
    </select>
  )
}
