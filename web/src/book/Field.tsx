import { useId, useState } from 'react'
import { tooLongMessage } from '../sync/limits'
import { SavedMark } from './SavedMark'
import { useSaved } from './useSaved'

type Props = {
  label: string
  /** What is saved, as text. */
  shown: string
  /**
   * Called when focus leaves and the text differs from [shown]. It gets the text and does nothing for a value that
   * means no change (an unchanged field never reaches the store). It returns true when it saved something: only
   * then is the "Saved" tick shown.
   */
  onCommit: (text: string) => boolean
  kind?: 'text' | 'number' | 'area'
  /** The most characters the server accepts: the box stops typing there, and a longer value is refused. */
  limit?: number
  /** The largest number a number box accepts: a bigger one is refused with a message, like `limit`. */
  max?: number
}

/** A box that keeps what is typed until focus leaves, then hands it over once; untouched boxes never save. */
export function Field({ label, shown, onCommit, kind = 'text', limit, max }: Props) {
  const id = useId()
  const [draft, setDraft] = useState<string | null>(null)
  const [tooLong, setTooLong] = useState(false)
  const [tooBig, setTooBig] = useState(false)
  const [saved, flash] = useSaved()
  const value = draft ?? shown
  const finish = (box: HTMLInputElement | HTMLTextAreaElement) => {
    if (draft === null) return
    setDraft(null)
    // Pasted or filled in past what the box allows: not saved, and said so.
    if (limit !== undefined && draft.length > limit) {
      setTooLong(true)
      return
    }
    setTooLong(false)
    if (max !== undefined && Number(draft) > max) {
      setTooBig(true)
      return
    }
    setTooBig(false)
    // A number box reports partly typed text ("12e", "-") as empty with badInput: that is a typo, not a clearing.
    if ('validity' in box && box.validity?.badInput) return
    if (draft !== shown && onCommit(draft)) flash()
  }
  const common = {
    id,
    value,
    maxLength: limit,
    onChange: (e: { target: { value: string } }) => setDraft(e.target.value),
    onBlur: (e: { currentTarget: HTMLInputElement | HTMLTextAreaElement }) => finish(e.currentTarget),
  }
  return (
    <div className={kind === 'area' ? 'field field--wide' : 'field'}>
      <div className="field__head">
        <label htmlFor={id}>{label}</label>
        <SavedMark saved={saved} />
      </div>
      {kind === 'area' ? (
        <textarea rows={4} {...common} />
      ) : (
        <input type={kind === 'number' ? 'number' : 'text'} min={kind === 'number' ? 0 : undefined} max={kind === 'number' ? max : undefined} {...common} />
      )}
      {tooBig && max !== undefined && <small role="alert">{`${label} can't be more than ${String(max)}.`}</small>}
      {tooLong && limit !== undefined && <small role="alert">{tooLongMessage(label, limit)}</small>}
    </div>
  )
}
