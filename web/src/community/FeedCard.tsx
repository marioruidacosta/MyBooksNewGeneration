import { useState, useSyncExternalStore } from 'react'
import { addBook } from '../add/addBook'
import type { Status } from '../sync/model'
import { STATUS_LABELS } from '../library/filters'
import { useStore } from '../sync/StoreContext'
import { findInLibrary } from '../text/keys'
import { useToast } from '../ui/toastContext'
import { communityMessage } from './api'
import { useCommunity } from './useCommunity'
import { REPORT_REASONS, type FeedBook, type FeedEntry, type Person, type ReportReason } from './model'
import { actionText, groupText, relativeTime, starsLabel, starsText, type FeedItem } from './text'
import { Avatar, CommunityCover, Dialog, PersonLink, PersonRow } from './ui'
import { useLoaded } from './useLoaded'

const REPORT_DETAILS_MAX = 1000

/** Adds a reader's book to the library, or opens the copy already there. */
function BookAction({ book }: { book: FeedBook }) {
  const store = useStore()
  const { nav } = useCommunity()
  const toast = useToast()
  const books = useSyncExternalStore(store.subscribe, () => store.books())
  const mine = findInLibrary({ isbn: book.isbn, title: book.title, authors: book.authors }, books)
  if (mine) {
    return (
      <button type="button" className="feed-book__mine" onClick={() => nav.openBook(mine.id)}>
        ✓ In your library · {STATUS_LABELS[mine.status]}
      </button>
    )
  }
  const add = (status: Status) => {
    const { id } = addBook(
      store,
      {
        isbn: book.isbn, title: book.title, authors: book.authors.split(',').map((a) => a.trim()).filter(Boolean),
        publisher: null, year: null, pages: book.pages, coverUrl: book.coverUrl, description: book.blurb,
      },
      Date.now(),
      status,
    )
    toast.show(`Added to ${STATUS_LABELS[status]}`, { label: 'Open', run: () => nav.openBook(id) })
  }
  return (
    <span className="feed-book__add">
      <button type="button" className="primary" onClick={() => add('TO_READ')}>Want to read</button>
      <button type="button" onClick={() => add('WISHLIST')}>To buy</button>
    </span>
  )
}

/** The book in its own panel: cover, title, author and pages, progress, the blurb, and what to do with it. */
function BookPanel({ book, progress }: { book: FeedBook; progress: number | null }) {
  const details = [book.authors, book.pages !== null ? `${book.pages} pages` : null].filter(Boolean).join(' · ')
  return (
    <div className="feed-book">
      <CommunityCover title={book.title} authors={book.authors} url={book.coverUrl} className="feed-book__cover" />
      <div className="feed-book__text">
        <p className="feed-book__title">{book.title}</p>
        {details && <p className="feed-book__details">{details}</p>}
        {progress !== null && (
          <span
            className="feed-progress"
            role="progressbar"
            aria-label={`Progress in ${book.title}`}
            aria-valuemin={0}
            aria-valuemax={100}
            aria-valuenow={progress}
          >
            <span style={{ width: `${Math.min(100, Math.max(0, progress))}%` }} />
          </span>
        )}
        {book.blurb && <p className="feed-book__blurb">{book.blurb}</p>}
        <BookAction book={book} />
      </div>
    </div>
  )
}

/** A review, cut to a few lines until asked for more. */
function Review({ text }: { text: string }) {
  const [open, setOpen] = useState(false)
  const long = text.length > 240
  return (
    <blockquote className={`feed-review ${long && !open ? 'feed-review--cut' : ''}`.trim()}>
      <p>{text}</p>
      {long && (
        <button type="button" className="quiet" aria-expanded={open} onClick={() => setOpen((o) => !o)}>
          {open ? 'Show less' : 'Show more'}
        </button>
      )}
    </blockquote>
  )
}

function LikersDialog({ activity, onClose }: { activity: number; onClose: () => void }) {
  const { api } = useCommunity()
  const { data, error, loading } = useLoaded(() => api.likers(activity), String(activity))
  return (
    <Dialog title="Liked by" onClose={onClose}>
      {loading && <p className="loading-inline">Loading…</p>}
      {error && <p className="error" role="alert">{error}</p>}
      {data && <ul className="person-list">{data.map((p) => <PersonRow key={p.id} person={p} />)}</ul>}
      <div className="dialog__actions">
        <button type="button" onClick={onClose}>Close</button>
      </div>
    </Dialog>
  )
}

/** Reports a person, or one of their posts. */
export function ReportDialog({ person, activity = null, onClose }: { person: Person; activity?: number | null; onClose: () => void }) {
  const { api } = useCommunity()
  const toast = useToast()
  const [reason, setReason] = useState<ReportReason>('spam')
  const [details, setDetails] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const send = async () => {
    setBusy(true)
    setError(null)
    try {
      await api.report(person.id, reason, details, activity)
      toast.show('Thanks. Your report was sent.')
      onClose()
    } catch (e) {
      setError(communityMessage(e))
      setBusy(false)
    }
  }
  return (
    <Dialog title={`Report ${person.displayName}`} onClose={onClose}>
      <fieldset className="report-reasons">
        <legend>What's wrong?</legend>
        {REPORT_REASONS.map((r) => (
          <label key={r.id}>
            <input type="radio" name="report-reason" checked={reason === r.id} onChange={() => setReason(r.id)} />
            {r.label}
          </label>
        ))}
      </fieldset>
      <label className="dialog__field">
        Anything to add? (optional)
        <textarea value={details} maxLength={REPORT_DETAILS_MAX} rows={3} onChange={(e) => setDetails(e.target.value)} />
      </label>
      {error && <p className="error" role="alert">{error}</p>}
      <div className="dialog__actions">
        <button type="button" className="quiet" onClick={onClose}>Cancel</button>
        <button type="button" className="primary" disabled={busy} onClick={() => void send()}>Send report</button>
      </div>
    </Dialog>
  )
}

function EntryCard({ entry, onChange, now }: { entry: FeedEntry; onChange: (entry: FeedEntry) => void; now: number }) {
  const { api, profile } = useCommunity()
  const toast = useToast()
  const [dialog, setDialog] = useState<'likers' | 'report' | null>(null)
  const [busy, setBusy] = useState(false)
  const mine = entry.actor.id === api.me
  const stars = starsText(entry.rating)
  const canLike = profile !== null

  const toggleLike = async () => {
    if (busy) return
    setBusy(true)
    const liked = !entry.likedByMe
    onChange({ ...entry, likedByMe: liked, likeCount: Math.max(0, entry.likeCount + (liked ? 1 : -1)) })
    try {
      await (liked ? api.like(entry.id) : api.unlike(entry.id))
    } catch (e) {
      onChange(entry)
      toast.show(communityMessage(e))
    } finally {
      setBusy(false)
    }
  }

  return (
    <li className="feed-card">
      <div className="feed-card__head">
        <Avatar person={entry.actor} />
        <p className="feed-card__line">
          <PersonLink person={entry.actor} /> {actionText(entry)}
          <time className="feed-card__time" dateTime={entry.createdAtText}>{relativeTime(entry.createdAt, now)}</time>
        </p>
      </div>
      {stars && <p className="feed-stars" role="img" aria-label={starsLabel(entry.rating) ?? undefined}>{stars}</p>}
      {entry.kind === 'reviewed' && entry.review && <Review text={entry.review} />}
      <BookPanel book={entry.book} progress={entry.kind === 'progress' ? entry.progress : null} />
      <div className="feed-card__actions">
        {!mine && (
          <button
            type="button"
            className={`feed-like ${entry.likedByMe ? 'feed-like--on' : ''}`.trim()}
            aria-pressed={entry.likedByMe}
            aria-label={`Like ${entry.actor.displayName}'s update on ${entry.book.title}`}
            disabled={!canLike}
            onClick={() => void toggleLike()}
          >
            {entry.likedByMe ? '♥' : '♡'}
          </button>
        )}
        {entry.likeCount > 0 && (
          <button type="button" className="quiet feed-likes" onClick={() => setDialog('likers')}>
            {entry.likeCount === 1 ? '1 like' : `${entry.likeCount} likes`}
          </button>
        )}
        {!mine && (
          <button type="button" className="quiet feed-report" onClick={() => setDialog('report')}>Report</button>
        )}
      </div>
      {dialog === 'likers' && <LikersDialog activity={entry.id} onClose={() => setDialog(null)} />}
      {dialog === 'report' && <ReportDialog person={entry.actor} activity={entry.id} onClose={() => setDialog(null)} />}
    </li>
  )
}

/** Several books someone added at once: up to four covers and "+N", which shows them all. */
function GroupCard({ item, now }: { item: Extract<FeedItem, { type: 'group' }>; now: number }) {
  const [open, setOpen] = useState(false)
  const [book, setBook] = useState<FeedBook | null>(null)
  const shown = open ? item.entries : item.entries.slice(0, 4)
  const more = item.entries.length - shown.length
  return (
    <li className="feed-card">
      <div className="feed-card__head">
        <Avatar person={item.actor} />
        <p className="feed-card__line">
          <PersonLink person={item.actor} /> {groupText(item.kind, item.entries.length)}
          <time className="feed-card__time" dateTime={item.entries[0].createdAtText}>
            {relativeTime(item.entries[0].createdAt, now)}
          </time>
        </p>
      </div>
      <div className="feed-group">
        {shown.map((e) => (
          <button key={e.id} type="button" className="feed-group__cover" aria-label={`Open ${e.book.title}`} onClick={() => setBook(e.book)}>
            <CommunityCover title={e.book.title} authors={e.book.authors} url={e.book.coverUrl} />
          </button>
        ))}
        {more > 0 && (
          <button type="button" className="feed-group__more" aria-label={`Show ${more} more books`} onClick={() => setOpen(true)}>
            +{more}
          </button>
        )}
      </div>
      {book && (
        <Dialog title={book.title} onClose={() => setBook(null)}>
          <BookPanel book={book} progress={null} />
          <div className="dialog__actions">
            <button type="button" onClick={() => setBook(null)}>Close</button>
          </div>
        </Dialog>
      )}
    </li>
  )
}

export function FeedList({
  items, onChange, now = Date.now(),
}: { items: FeedItem[]; onChange: (entry: FeedEntry) => void; now?: number }) {
  return (
    <ul className="feed-list">
      {items.map((item) =>
        item.type === 'single'
          ? <EntryCard key={item.key} entry={item.entry} onChange={onChange} now={now} />
          : <GroupCard key={item.key} item={item} now={now} />,
      )}
    </ul>
  )
}
