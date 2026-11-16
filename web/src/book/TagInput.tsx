import { useId, useState, type KeyboardEvent } from 'react'
import { BOOK_LIMITS, tooLongMessage } from '../sync/limits'
import { SavedMark } from './SavedMark'
import { useSaved } from './useSaved'

type Props = {
  label: string
  tags: string[]
  /** Tags already used in the library, offered as you type. */
  suggestions: string[]
  /** Saves the whole list of tags. */
  onChange: (tags: string[]) => void
}

/**
 * The book's tags as chips with a box to add more: Enter or a comma adds what is typed, the × on a chip (or Backspace in
 * the empty box) removes one. Every change is saved at once; a list longer than the server accepts is refused.
 */
export function TagInput({ label, tags, suggestions, onChange }: Props) {
  const id = useId()
  const listId = `${id}-tags`
  const [text, setText] = useState('')
  const [tooLong, setTooLong] = useState(false)
  const [saved, flash] = useSaved()

  // A phone can send the same tag twice: it shows once, and the next save leaves one.
  const shown = [...new Set(tags)]
  const save = (next: string[]) => {
    setTooLong(false)
    onChange(next)
    flash()
  }
  /** Adds the tags in [parts] that aren't there yet, in order; false when the list would be too long. */
  const add = (parts: string[]) => {
    const next = [...shown]
    for (const part of parts) {
      const tag = part.trim()
      if (tag !== '' && !next.includes(tag)) next.push(tag)
    }
    if (next.join(', ').length > BOOK_LIMITS.tags) {
      setTooLong(true)
      return false
    }
    setTooLong(false)
    if (next.length > shown.length) save(next)
    return true
  }

  const change = (value: string) => {
    setTooLong(false)
    if (!value.includes(',')) {
      setText(value)
      return
    }
    // A comma finishes a tag; what follows the last comma is the start of the next.
    const parts = value.split(',')
    const rest = parts.pop() ?? ''
    setText(add(parts) ? rest : value)
  }
  const keyDown = (e: KeyboardEvent<HTMLInputElement>) => {
    if (e.key === 'Enter') {
      e.preventDefault()
      if (e.nativeEvent.isComposing) return
      if (add([text])) setText('')
    } else if (e.key === 'Backspace' && text === '' && shown.length > 0 && !e.repeat) {
      save(shown.slice(0, -1))
    }
  }
  const open = suggestions.filter((s) => !shown.includes(s))

  return (
    <div className="field field--wide">
      <div className="field__head">
        <label htmlFor={id}>{label}</label>
        <SavedMark saved={saved} />
      </div>
      <div className="tag-box">
        {shown.map((t, i) => (
          <span key={`${String(i)}-${t}`} className="tag-chip">
            {t}
            <button type="button" aria-label={`Remove tag ${t}`} onClick={() => save(shown.filter((x) => x !== t))}>
              <span aria-hidden="true">×</span>
            </button>
          </span>
        ))}
        <input
          id={id}
          type="text"
          list={listId}
          value={text}
          autoComplete="off"
          onChange={(e) => change(e.target.value)}
          onKeyDown={keyDown}
          onBlur={() => {
            if (text.trim() !== '' && add([text])) setText('')
          }}
        />
        <datalist id={listId}>
          {open.map((s) => (
            <option key={s} value={s} />
          ))}
        </datalist>
      </div>
      {tooLong && <small role="alert">{tooLongMessage(label, BOOK_LIMITS.tags)}</small>}
    </div>
  )
}
