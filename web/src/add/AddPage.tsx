import { useEffect, useState, useSyncExternalStore } from 'react'
import type { LibraryStore } from '../sync/store'
import { addBook, inLibrary } from './addBook'
import { defaultLookup, isbnQuery, NotFoundWithErrors, type Found, type Lookup } from './lookup'
import { PasteIsbns } from './PasteIsbns'

const UNREACHED = "Not found. A source couldn't be reached. Try again later."
const NO_RESULTS_UNREACHED = "No results. A source couldn't be reached. Try again later."

/** At about this many characters a description would pass four lines. */
const LONG_DESCRIPTION = 280

/** A source's description: text only, clamped to about four lines with More/Less when long. */
function Description({ text }: { text: string }) {
  const [open, setOpen] = useState(false)
  const long = text.length > LONG_DESCRIPTION
  return (
    <>
      {/* The description is untrusted: React renders it as text. */}
      <p className={long && !open ? 'description clamped' : 'description'}>{text}</p>
      {long && (
        <button type="button" aria-expanded={open} onClick={() => setOpen(!open)}>
          {open ? 'Less' : 'More'}
        </button>
      )}
    </>
  )
}

type Props = {
  store: LibraryStore
  lookup?: Lookup
  onOpen: (id: string) => void
  /** An ISBN to look up as the page opens (from the jump box). */
  initialIsbn?: string
}

/** Add books: a search box (an ISBN or a title) and a list of pasted ISBNs. */
export function AddPage({ store, lookup = defaultLookup, onOpen, initialIsbn }: Props) {
  const books = useSyncExternalStore(store.subscribe, () => store.books())
  const [query, setQuery] = useState(initialIsbn ?? '')
  const [results, setResults] = useState<Found[] | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [added, setAdded] = useState<{ id: string; title: string } | null>(null)

  const search = async (text: string = query) => {
    const q = text.trim()
    if (q === '') return
    setError('')
    setAdded(null)
    setResults(null)
    const asIsbn = isbnQuery(q)
    if (asIsbn.kind === 'invalid') {
      setError("That ISBN isn't valid. Check the digits.")
      return
    }
    setBusy(true)
    try {
      if (asIsbn.kind === 'valid') {
        const f = await lookup.lookupIsbn(asIsbn.isbn13)
        setResults(f ? [f] : [])
      } else {
        setResults(await lookup.searchTitle(q))
      }
    } catch (e) {
      const unreached = e instanceof NotFoundWithErrors
      setError(unreached ? (asIsbn.kind === 'valid' ? UNREACHED : NO_RESULTS_UNREACHED) : "Couldn't search just now. Try again.")
    } finally {
      setBusy(false)
    }
  }

  // Each ISBN the jump box hands over is looked up once, as the page opens.
  useEffect(() => {
    if (initialIsbn) void search(initialIsbn)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [initialIsbn])

  const add = (found: Found) => {
    const r = addBook(store, found)
    if (r.added) setAdded({ id: r.id, title: found.title })
    else onOpen(r.id)
  }

  return (
    <main className="add-page">
      <h1>Add a book</h1>
      <form
        onSubmit={(e) => {
          e.preventDefault()
          void search()
        }}
      >
        <label>
          ISBN or title
          <input type="search" value={query} onChange={(e) => setQuery(e.target.value)} />
        </label>
        <button type="submit" disabled={busy}>
          Search
        </button>
      </form>
      {busy && <p className="loading">Searching…</p>}
      {error && (
        <p className="error" role="alert">
          {error}
        </p>
      )}
      {added && (
        <p role="status">
          Added {added.title}.{' '}
          <button type="button" onClick={() => onOpen(added.id)}>
            Open it
          </button>
        </p>
      )}
      {results && results.length === 0 && <p>Not found</p>}
      {results && results.length > 0 && (
        <ul className="add-results">
          {results.map((f, i) => {
            const have = inLibrary(f, books)
            return (
              <li key={`${f.isbn ?? 'none'}-${String(i)}`}>
                <strong>{f.title}</strong> <span>{f.authors.join(', ')}</span>
                {f.year !== null && <span> ({f.year})</span>}
                {f.description && <Description text={f.description} />}
                {have && <span> Already in your library</span>}{' '}
                <button type="button" onClick={() => add(f)}>
                  {have ? 'Open' : 'Add'}
                </button>
              </li>
            )
          })}
        </ul>
      )}
      <PasteIsbns store={store} lookup={lookup} books={books} />
    </main>
  )
}
