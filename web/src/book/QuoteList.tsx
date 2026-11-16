import { useState, useSyncExternalStore } from 'react'
import { MAX_QUOTE_TEXT, tooLongMessage } from '../sync/limits'
import type { LibraryStore } from '../sync/store'
import { Field } from './Field'
import { parseWhole } from './parse'

/** A book's quotes: each saved when focus leaves it, a form to add one, a button to delete. */
export function QuoteList({ store, bookId }: { store: LibraryStore; bookId: string }) {
  const quotes = useSyncExternalStore(store.subscribe, () => store.quotes(bookId))
  const [text, setText] = useState('')
  const [page, setPage] = useState('')
  const [tooLong, setTooLong] = useState(false)
  const [problem, setProblem] = useState('')

  const add = () => {
    const body = text.trim()
    const n = parseWhole(page)
    // The server refuses a longer quote (and then every later change to it), so it is never saved.
    setTooLong(body.length > MAX_QUOTE_TEXT)
    setProblem(body === '' ? 'Write the quote first.' : n === undefined ? (Number(page) < 0 ? 'The page must be 0 or more.' : 'The page must be a whole number.') : '')
    if (body === '' || n === undefined || body.length > MAX_QUOTE_TEXT) return
    store.createQuote({
      id: crypto.randomUUID(), bookId, text: body, page: n, dateAdded: Date.now(), position: null, deleted: false,
    })
    setText('')
    setPage('')
  }

  return (
    <section className="quotes" aria-labelledby="quotes-title">
      <h2 id="quotes-title">Quotes</h2>
      <ul>
        {quotes.map((q, i) => (
          <li key={q.id} className="quote">
            <Field
              kind="area"
              label={`Quote ${i + 1} text`}
              shown={q.text}
              limit={MAX_QUOTE_TEXT}
              onCommit={(t) => {
                // A quote can't be emptied; delete it instead.
                if (t.trim() !== '' && t !== q.text) store.edit('quotes', q.id, { text: t })
              }}
            />
            <Field
              kind="number"
              label={`Quote ${i + 1} page`}
              shown={q.page === null ? '' : String(q.page)}
              onCommit={(t) => {
                const n = parseWhole(t)
                if (n !== undefined && n !== q.page) store.edit('quotes', q.id, { page: n })
              }}
            />
            <button type="button" className="danger" onClick={() => store.remove('quotes', q.id)}>
              {`Delete quote ${i + 1}`}
            </button>
          </li>
        ))}
      </ul>
      <div className="quote quote--new">
        <label className="field field--wide">
          <span>New quote</span>
          <textarea rows={3} maxLength={MAX_QUOTE_TEXT} value={text} onChange={(e) => setText(e.target.value)} />
          {tooLong && <small role="alert">{tooLongMessage('New quote', MAX_QUOTE_TEXT)}</small>}
        </label>
        <label className="field">
          <span>Quote page</span>
          <input type="number" min={0} value={page} onChange={(e) => setPage(e.target.value)} />
        </label>
        <button type="button" className="primary" onClick={add}>
          Add quote
        </button>
        {problem && <small role="alert">{problem}</small>}
      </div>
    </section>
  )
}
