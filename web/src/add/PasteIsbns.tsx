import { useRef, useState } from 'react'
import { flushSync } from 'react-dom'
import type { Book } from '../sync/model'
import type { LibraryStore } from '../sync/store'
import { addBook, authorsText, inLibrary } from './addBook'
import { normalizeIsbn, NotFoundWithErrors, type Found, type Lookup } from './lookup'

export const PASTE_CONCURRENCY = 4

const UNREACHED = "Not found. A source couldn't be reached. Try again later."

type Line =
  | { raw: string; state: 'waiting' | 'looking' | 'notfound' | 'unreached' | 'invalid' | 'error' }
  | { raw: string; state: 'found'; found: Found }

/** Runs `task` over `items`, at most `limit` at a time. */
async function inParallel<T>(items: T[], limit: number, task: (item: T, index: number) => Promise<void>) {
  let next = 0
  const worker = async () => {
    while (next < items.length) {
      const i = next++
      await task(items[i], i)
    }
  }
  await Promise.all(Array.from({ length: Math.min(limit, items.length) }, worker))
}

type Props = { store: LibraryStore; lookup: Lookup; books: Book[] }

/** One ISBN per line, each looked up and shown; "Add N books" adds the ones found and new. */
export function PasteIsbns({ store, lookup, books }: Props) {
  const [textValue, setText] = useState('')
  const [lines, setLines] = useState<Line[]>([])
  const [busy, setBusy] = useState(false)
  const [message, setMessage] = useState('')
  const [adding, setAdding] = useState(false)
  // Set synchronously, so a second click before the next render finds the work already done.
  const addingRef = useRef(false)

  const set = (i: number, line: Line) => setLines((old) => old.map((l, k) => (k === i ? line : l)))

  const run = async () => {
    // One line per book: two spellings of one ISBN are the same line.
    const byIsbn = new Map<string, string>()
    for (const l of textValue.split('\n').map((x) => x.trim()).filter((x) => x !== '')) {
      const key = normalizeIsbn(l) ?? l
      if (!byIsbn.has(key)) byIsbn.set(key, l)
    }
    const raws = [...byIsbn.values()]
    setMessage('')
    addingRef.current = false
    setAdding(false)
    setLines(raws.map((raw) => ({ raw, state: 'waiting' })))
    setBusy(true)
    try {
      await inParallel(raws, PASTE_CONCURRENCY, async (raw, i) => {
        const isbn = normalizeIsbn(raw)
        if (!isbn) return set(i, { raw, state: 'invalid' })
        set(i, { raw, state: 'looking' })
        try {
          const found = await lookup.lookupIsbn(isbn)
          set(i, found ? { raw, state: 'found', found } : { raw, state: 'notfound' })
        } catch (e) {
          set(i, { raw, state: e instanceof NotFoundWithErrors ? 'unreached' : 'error' })
        }
      })
    } finally {
      setBusy(false)
    }
  }

  const known = (l: Line) => l.state === 'found' && inLibrary(l.found, books) !== undefined
  // The books to add: new to the library, and distinct from each other (same ISBN, or same title and first author).
  const toAdd: (Line & { state: 'found'; found: Found })[] = []
  const batch: Book[] = []
  for (const l of lines) {
    if (l.state !== 'found' || known(l) || inLibrary(l.found, batch)) continue
    toAdd.push(l)
    batch.push({ id: l.raw, isbn: l.found.isbn, title: l.found.title, authors: authorsText(l.found, books) } as Book)
  }

  const addAll = () => {
    if (addingRef.current) return
    addingRef.current = true
    // Shown at once, before the work: the button really is disabled while the books are added.
    flushSync(() => setAdding(true))
    try {
      let n = 0
      for (const l of toAdd) if (addBook(store, l.found).added) n += 1
      setMessage(n === 1 ? 'Added 1 book' : `Added ${String(n)} books`)
    } finally {
      addingRef.current = false
      setAdding(false)
    }
  }

  const label = (l: Line) => {
    switch (l.state) {
      case 'waiting':
        return 'Waiting…'
      case 'looking':
        return 'Looking…'
      case 'notfound':
        return 'Not found'
      case 'unreached':
        return UNREACHED
      case 'invalid':
        return 'Not a valid ISBN'
      case 'error':
        return "Couldn't look this up"
      case 'found':
        return known(l) ? 'Already in your library' : 'Found'
    }
  }

  return (
    <section aria-label="Paste ISBNs">
      <h2>Paste ISBNs</h2>
      <label>
        ISBNs, one per line
        <textarea value={textValue} onChange={(e) => setText(e.target.value)} rows={6} />
      </label>
      <button type="button" onClick={() => void run()} disabled={busy || textValue.trim() === ''}>
        Look up
      </button>
      {lines.length > 0 && (
        <ul className="paste-results">
          {lines.map((l) => (
            <li key={l.raw}>
              <span>{l.state === 'found' ? `${l.found.title} (${l.raw})` : l.raw}</span> <span>{label(l)}</span>
            </li>
          ))}
        </ul>
      )}
      {toAdd.length > 0 && !busy && (
        <button type="button" className="primary" onClick={addAll} disabled={adding}>
          {toAdd.length === 1 ? 'Add 1 book' : `Add ${String(toAdd.length)} books`}
        </button>
      )}
      {message && <p role="status">{message}</p>}
    </section>
  )
}
