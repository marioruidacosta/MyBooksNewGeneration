import { useEffect, useRef, useState } from 'react'
import type { Book } from '../sync/model'
import { parseProgress, type ProgressInput } from './progress'

/**
 * "Page [ ] of N" (a percentage when the page count is unknown) for one book. Enter or leaving the field saves, Escape
 * cancels (both hand focus back to the button that opened it: [onClose] is told so; leaving by blur doesn't);  a value that isn't a page or a percentage is not saved, and the editor stays open and says what to type.
 */
export function ProgressEditor({ book, onSave, onClose }: { book: Book; onSave: (input: ProgressInput) => void; onClose: (returnFocus: boolean) => void }) {
  const byPages = book.pageCount !== null && book.pageCount > 0
  const start = byPages ? book.currentPage : book.progressPercent
  const [text, setText] = useState(start === null ? '' : String(start))
  const [invalid, setInvalid] = useState(false)
  const input = useRef<HTMLInputElement>(null)
  // Set once the editor has been settled (saved or cancelled), so closing it does not settle it again on blur.
  const settled = useRef(false)

  useEffect(() => {
    input.current?.focus()
    input.current?.select()
  }, [])

  const finish = (save: boolean, byKey = false) => {
    if (settled.current) return
    if (save && text.trim() !== '') {
      const n = parseProgress(text, byPages)
      if (n === null) {
        setInvalid(true)
        return
      }
      onSave(byPages ? { page: n } : { percent: n })
    }
    settled.current = true
    onClose(byKey)
  }

  return (
    <span className="shelf-edit">
      <span className="shelf-edit__row">
      {byPages && <span aria-hidden="true">Page</span>}
      <input
        ref={input}
        type="number"
        min={0}
        max={byPages ? undefined : 100}
        inputMode="numeric"
        aria-label={byPages ? `Current page of ${book.title}` : `Percent read of ${book.title}`}
        aria-invalid={invalid || undefined}
        value={text}
        onChange={(e) => {
          setText(e.target.value)
          setInvalid(false)
        }}
        onBlur={() => finish(true)}
        onKeyDown={(e) => {
          if (e.key === 'Enter') {
            e.preventDefault()
            finish(true, true)
          } else if (e.key === 'Escape') {
            e.preventDefault()
            finish(false, true)
          }
        }}
      />
      <span aria-hidden="true">{byPages ? `of ${String(book.pageCount)}` : '%'}</span>
      </span>
      {invalid && (
        <span className="shelf-edit__error" role="alert">
          {byPages ? 'Type a page number' : 'Type a percentage from 0 to 100'}
        </span>
      )}
    </span>
  )
}
