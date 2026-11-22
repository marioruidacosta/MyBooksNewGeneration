import { useEffect, useState, useSyncExternalStore } from 'react'
import { addBook } from '../add/addBook'
import { normalizeIsbn } from '../add/lookup'
import { STATUS_LABELS } from '../library/filters'
import type { Status } from '../sync/model'
import { useStore } from '../sync/StoreContext'
import { useToast } from '../ui/toastContext'
import { ReportDialog } from './FeedCard'
import { foundFrom } from './suggestedBook'
import type { BookSuggestion } from './model'
import { relativeTime } from './text'
import { Avatar, CommunityCover, PersonLink } from './ui'
import { useCommunity } from './useCommunity'

function SuggestionCard({ suggestion, onReport }: { suggestion: BookSuggestion; onReport: () => void }) {
  const { inbox, nav } = useCommunity()
  const store = useStore()
  const toast = useToast()
  const [busy, setBusy] = useState(false)
  const { book, sender, note } = suggestion
  const details = [book.authors, book.pages !== null ? `${book.pages} pages` : null].filter(Boolean).join(' · ')

  const accept = async (status: Status) => {
    if (busy) return
    setBusy(true)
    // The ISBN came from another person's device: one the library can't use mustn't be stored.
    const found = foundFrom({ ...book, isbn: book.isbn ? normalizeIsbn(book.isbn) : null })
    const { id, added } = addBook(store, found, Date.now(), status)
    toast.show(added ? `Added to ${STATUS_LABELS[status]}` : 'Already in your library', { label: 'Open', run: () => nav.openBook(id) })
    await inbox.close(suggestion.id)
    setBusy(false)
  }

  const dismiss = async () => {
    if (busy) return
    setBusy(true)
    if (!(await inbox.close(suggestion.id))) toast.show("Couldn't dismiss the suggestion")
    setBusy(false)
  }

  return (
    <li className="feed-card">
      <div className="feed-card__head">
        <Avatar person={sender} />
        <p className="feed-card__line">
          <PersonLink person={sender} /> suggested a book
          <time className="feed-card__time" dateTime={new Date(suggestion.createdAt).toISOString()}>
            {relativeTime(suggestion.createdAt)}
          </time>
        </p>
      </div>
      {note && <blockquote className="suggestion-note">{`“${note}”`}</blockquote>}
      <div className="feed-book">
        <CommunityCover title={book.title} authors={book.authors} url={book.coverUrl} className="feed-book__cover" />
        <div className="feed-book__text">
          <p className="feed-book__title">{book.title}</p>
          {details && <p className="feed-book__details">{details}</p>}
          {book.blurb && <p className="feed-book__blurb">{book.blurb}</p>}
          <span className="feed-book__add">
            <button type="button" className="primary" disabled={busy} aria-label={`Want to read ${book.title}`} onClick={() => void accept('TO_READ')}>
              Want to read
            </button>
            <button type="button" disabled={busy} aria-label={`To buy ${book.title}`} onClick={() => void accept('WISHLIST')}>
              To buy
            </button>
            <button type="button" className="quiet" disabled={busy} aria-label={`Dismiss ${book.title}`} onClick={() => void dismiss()}>
              Dismiss
            </button>
          </span>
        </div>
      </div>
      <div className="feed-card__actions">
        <button type="button" className="quiet feed-report" aria-label={`Report suggestion of ${book.title}`} onClick={onReport}>
          Report
        </button>
      </div>
    </li>
  )
}

/** "Suggested to you", at the top of Community on both tabs. Being shown here is what marks them as seen. */
export function SuggestedToYou() {
  const { inbox } = useCommunity()
  const items = useSyncExternalStore(inbox.subscribe, inbox.items)
  const [reporting, setReporting] = useState<BookSuggestion | null>(null)

  useEffect(() => {
    void inbox.refresh()
  }, [inbox])
  useEffect(() => {
    inbox.markSeen()
  }, [inbox, items])

  if (items.length === 0) return null
  return (
    <section className="suggested" aria-labelledby="suggested-title">
      <h2 id="suggested-title">Suggested to you</h2>
      <ul className="feed-list">
        {items.map((s) => <SuggestionCard key={s.id} suggestion={s} onReport={() => setReporting(s)} />)}
      </ul>
      {reporting && (
        <ReportDialog person={reporting.sender} note={reporting.note} onClose={() => setReporting(null)} />
      )}
    </section>
  )
}
