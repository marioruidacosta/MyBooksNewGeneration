import { useState } from 'react'
import { tooLongMessage } from '../sync/limits'

type Props = {
  label: string
  /** What is saved, as text. */
  shown: string
  /**
   * Called when focus leaves and the text differs from [shown]. It gets the text and does nothing for a value that
   * means no change (an unchanged field never reaches the store).
   */
  onCommit: (text: string) => void
  kind?: 'text' | 'number' | 'date' | 'area'
  /** The most characters the server accepts: the box stops typing there, and a longer value is refused. */
  limit?: number
}

/** A box that keeps what is typed until focus leaves, then hands it over once; untouched boxes never save. */
export function Field({ label, shown, onCommit, kind = 'text', limit }: Props) {
  const [draft, setDraft] = useState<string | null>(null)
  const [tooLong, setTooLong] = useState(false)
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
    // A number box reports partly typed text ("12e", "-") as empty with badInput: that is a typo, not a clearing.
    if ('validity' in box && box.validity?.badInput) return
    if (draft !== shown) onCommit(draft)
  }
  const common = {
    value,
    maxLength: limit,
    onChange: (e: { target: { value: string } }) => setDraft(e.target.value),
    onBlur: (e: { currentTarget: HTMLInputElement | HTMLTextAreaElement }) => finish(e.currentTarget),
  }
  return (
    <label className={kind === 'area' ? 'field field--wide' : 'field'}>
      <span>{label}</span>
      {kind === 'area' ? (
        <textarea rows={4} {...common} />
      ) : (
        <input type={kind === 'number' ? 'number' : kind === 'date' ? 'date' : 'text'} min={kind === 'number' ? 0 : undefined} {...common} />
      )}
      {tooLong && limit !== undefined && <small role="alert">{tooLongMessage(label, limit)}</small>}
    </label>
  )
}
