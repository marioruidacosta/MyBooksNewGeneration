import { useEffect, useRef, useState } from 'react'
import { CoverImage } from '../covers/CoverImage'
import { STATUS_LABELS } from '../library/filters'
import type { Book } from '../sync/model'
import { formatDay } from '../text/dates'
import { authorKey, searchKey } from '../text/keys'
import { bookFacts, factRows, type BookFacts } from './wikidataFacts'
import { authorInfo, type AuthorInfo } from './wikipediaAuthor'

const PANELS = ['Cover', 'Details', 'Author'] as const

/** The details panel: label and value per line, as the app's bookDetails. */
function bookDetails(b: Book): [string, string][] {
  const rows: [string, string | null][] = [
    ['Publisher', b.publisher?.trim() ? b.publisher : null],
    ['Published', b.publishedYear !== null ? String(b.publishedYear) : null],
    ['Pages', b.pageCount !== null ? String(b.pageCount) : null],
    ['ISBN', b.isbn],
    ['Added', b.dateAdded > 0 ? formatDay(b.dateAdded) : null],
    ['Started', b.dateStarted !== null ? formatDay(b.dateStarted) : null],
    ['Finished', b.dateRead !== null ? formatDay(b.dateRead) : null],
  ]
  return rows.filter((r): r is [string, string] => r[1] !== null)
}

const otherCount = (n: number) => `${String(n)} other ${n === 1 ? 'book' : 'books'}`

type Author = { name: string; info: AuthorInfo | null; loading: boolean }

function AuthorPanel({ name, author, others, onOpenBook }: { name: string; author: Author | null; others: Book[]; onOpenBook?: (id: string) => void }) {
  if (name === '') return <p className="hint">No author recorded for this book.</p>
  const info = author?.info ?? null
  return (
    <div className="author-panel">
      <div className="author-panel__head">
        {info?.photoUrl && <img src={info.photoUrl} alt={name} />}
        <div>
          <strong>{info?.name ?? name}</strong>
          {info?.description && <span>{info.description}</span>}
        </div>
      </div>
      {info ? (
        <>
          <p>{info.extract}</p>
          {info.pageUrl && (
            <a href={info.pageUrl} target="_blank" rel="noopener noreferrer">
              Wikipedia
            </a>
          )}
        </>
      ) : (
        author?.loading !== false && <p className="author-panel__wait">Looking up {name}…</p>
      )}
      <p className="author-panel__library">
        {others.length === 0 ? 'No other books by them in your library' : `In your library · ${otherCount(others.length)}`}
      </p>
      {others.length > 0 && (
        <ul>
          {others.map((o) => (
            <li key={o.id}>
              <button type="button" onClick={() => onOpenBook?.(o.id)}>
                <span>{o.title}</span>
                <span>{o.status === 'READ' && o.rating !== null ? '★'.repeat(o.rating) : STATUS_LABELS[o.status]}</span>
              </button>
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}

type Props = {
  book: Book
  /** The library, for the author's other books. */
  books: Book[]
  onOpenBook?: (id: string) => void
  /** Finds the author's biography; Wikipedia when left out. */
  fetchAuthor?: (name: string) => Promise<AuthorInfo | null>
  /** Finds what is known about the work (awards, first published, …); Wikidata when left out. */
  fetchFacts?: (title: string, authors: string) => Promise<BookFacts | null>
}

/**
 * The phone's cover, as in the app: swiping it shows the book's details, then its author, in its place (three dots
 * show which). The author's biography is fetched only when their panel is first shown.
 */
export function CoverSlider({ book, books, onOpenBook, fetchAuthor = authorInfo, fetchFacts = bookFacts }: Props) {
  const track = useRef<HTMLDivElement>(null)
  const [current, setCurrent] = useState(0)
  const [author, setAuthor] = useState<Author | null>(null)
  const [facts, setFacts] = useState<BookFacts | null>(null)
  const started = useRef(false)
  const factsAsked = useRef(false)
  const name = book.authors.split(',')[0].trim()

  // Like the author, the work's facts are fetched only when the details are first shown.
  useEffect(() => {
    if (current !== 1 || factsAsked.current) return
    factsAsked.current = true
    fetchFacts(book.title, book.authors).then(setFacts, () => setFacts(null))
  }, [current, book.title, book.authors, fetchFacts])

  useEffect(() => {
    if (current !== 2 || started.current || name === '') return
    started.current = true
    setAuthor({ name, info: null, loading: true })
    fetchAuthor(name).then(
      (info) => setAuthor({ name, info, loading: false }),
      () => setAuthor({ name, info: null, loading: false }),
    )
  }, [current, name, fetchAuthor])

  const key = authorKey(name)
  const others = name === ''
    ? []
    : books
      .filter((b) => b.id !== book.id && authorKey(b.authors.split(',')[0].trim()) === key)
      .sort((a, b) => Number(a.status !== 'READ') - Number(b.status !== 'READ') || searchKey(a.title).localeCompare(searchKey(b.title)))

  const go = (i: number) => {
    setCurrent(i)
    const el = track.current
    el?.scrollTo?.({ left: i * el.clientWidth, behavior: 'smooth' })
  }

  return (
    <div className="cover-slider">
      <div
        ref={track}
        className="cover-slider__track"
        onScroll={(e) => {
          const el = e.currentTarget
          if (el.clientWidth > 0) setCurrent(Math.min(2, Math.max(0, Math.round(el.scrollLeft / el.clientWidth))))
        }}
      >
        <div className="cover-slider__panel" role="tabpanel" aria-label="Cover">
          <CoverImage book={book} className="cover-large" />
        </div>
        <div className="cover-slider__panel" role="tabpanel" aria-label="Details">
          <div className="cover-details">
            <dl>
              {[...bookDetails(book), ...(facts ? factRows(facts, book.title) : [])].map(([label, value]) => (
                <div key={label}>
                  <dt>{label}</dt>
                  <dd>{value}</dd>
                </div>
              ))}
            </dl>
            {facts && (
              <a href={facts.pageUrl} target="_blank" rel="noopener noreferrer">
                Wikidata
              </a>
            )}
          </div>
        </div>
        <div className="cover-slider__panel cover-slider__panel--top" role="tabpanel" aria-label="Author">
          <AuthorPanel name={name} author={author} others={others} onOpenBook={onOpenBook} />
        </div>
      </div>
      <div className="cover-dots" role="tablist" aria-label="Cover, details and author">
        {PANELS.map((p, i) => (
          <button key={p} type="button" role="tab" aria-label={p} aria-selected={i === current} onClick={() => go(i)} />
        ))}
      </div>
    </div>
  )
}
