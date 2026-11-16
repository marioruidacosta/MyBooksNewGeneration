import { useId, useRef, useState } from 'react'
import { dayFieldText, parseDayText } from '../text/dates'
import { dayText, parseDay } from './parse'
import { SavedMark } from './SavedMark'
import { useSaved } from './useSaved'

type Props = {
  label: string
  /** The saved day (local midnight), or null for none. */
  value: number | null
  /** Called with the day typed or picked (null when cleared). Returns true when it saved. */
  onCommit: (day: number | null) => boolean
}

/**
 * A day typed as dd/mm/yyyy (also 27-10-2026, 27.10.2026, 2026-10-27, 27/10/26), with a button that opens the browser's
 * calendar. A typo goes back to what was saved; empty clears the day; the same day again saves nothing.
 */
export function DateField({ label, value, onCommit }: Props) {
  const id = useId()
  const [draft, setDraft] = useState<string | null>(null)
  const [saved, flash] = useSaved()
  const [typo, setTypo] = useState(false)
  const picker = useRef<HTMLInputElement>(null)

  const commit = (next: number | null | undefined) => {
    if (next === undefined || dayText(next) === dayText(value)) return
    if (onCommit(next)) flash()
  }
  const openPicker = () => {
    const box = picker.current
    if (!box) return
    try {
      if (typeof box.showPicker === 'function') box.showPicker()
      else box.focus()
    } catch {
      box.focus()
    }
  }

  return (
    <div className="field">
      <div className="field__head">
        <label htmlFor={id}>{label}</label>
        <SavedMark saved={saved} />
      </div>
      <div className="date-box">
        <input
          id={id}
          type="text"
          inputMode="numeric"
          placeholder="dd/mm/yyyy"
          value={draft ?? dayFieldText(value)}
          onChange={(e) => {
            setDraft(e.target.value)
            setTypo(false)
          }}
          onBlur={() => {
            if (draft === null) return
            setDraft(null)
            const day = parseDayText(draft)
            setTypo(day === undefined)
            commit(day)
          }}
        />
        <button type="button" className="date-pick" aria-label={`Pick a date for ${label}`} onClick={openPicker}>
          <svg viewBox="0 0 16 16" width="16" height="16" aria-hidden="true" focusable="false">
            <path fill="none" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" d="M2.5 4.5h11v9h-11zM2.5 7h11M5.5 2.5v3M10.5 2.5v3" />
          </svg>
        </button>
        <input
          ref={picker}
          type="date"
          className="date-native"
          tabIndex={-1}
          aria-hidden="true"
          value={dayText(value)}
          onChange={(e) => {
            setTypo(false)
            commit(parseDay(e.target.value))
          }}
        />
      </div>
      {typo && <small role="alert">Type a date as dd/mm/yyyy</small>}
    </div>
  )
}
