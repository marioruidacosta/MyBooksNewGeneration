import { useContext, useState, useSyncExternalStore } from 'react'
import { SharingContext } from '../share/SharingContext'
import type { Book } from '../sync/model'
import { useOptionalCommunity, type Community } from './useCommunity'
import { BOOK_REVIEWS_PAGE, NO_SCORE, type BookReview, type BookScore } from './model'
import { friendWithBookText, relativeTime, starsLabel, starsText } from './text'
import { Avatar, PersonLink, PersonRow } from './ui'
import { useLoaded } from './useLoaded'

const never = () => () => {}

/** "Don't share this book": the same switch on every device. */
export function ShareSwitch({ id }: { id: string }) {
  const publisher = useContext(SharingContext)
  const community = useOptionalCommunity()
  const excluded = useSyncExternalStore(publisher ? publisher.subscribe : never, () => publisher?.isExcluded(id) ?? false)
  if (!publisher || !community?.profile?.shareActivity) return null
  return (
    <label className="switch-row share-switch">
      <input type="checkbox" role="switch" checked={excluded} onChange={(e) => publisher.setExcluded(id, e.target.checked)} />
      Don't share this book
    </label>
  )
}

function FriendsWithBook({ community, isbn }: { community: Community; isbn: string }) {
  const { data } = useLoaded(() => community.api.friendsWithBook(isbn), `friends:${isbn}`)
  if (!data || data.length === 0) return null
  return (
    <section className="book-community__part" aria-labelledby="book-friends">
      <h3 id="book-friends">Friends who have this book</h3>
      <ul className="person-list">
        {data.map((f) => (
          <PersonRow
            key={f.person.id}
            person={f.person}
            detail={
              <>
                {friendWithBookText(f)}
                {f.rating !== null && <span role="img" aria-label={starsLabel(f.rating) ?? undefined}> · {starsText(f.rating)}</span>}
                {f.review && <span className="book-community__quote">“{f.review}”</span>}
              </>
            }
          />
        ))}
      </ul>
    </section>
  )
}

function ScoreBars({ score }: { score: BookScore }) {
  return (
    <div className="score">
      <p className="score__average">
        <strong>{score.average?.toFixed(1)}</strong>
        <span role="img" aria-label={`Average ${score.average?.toFixed(1)} out of 5`}> {starsText(Math.round(score.average ?? 0))}</span>
        <span className="score__count"> {score.ratings === 1 ? '1 rating' : `${score.ratings} ratings`}</span>
      </p>
      <ul className="score__bars">
        {score.perScore.map((count, i) => (
          <li key={5 - i}>
            <span>{5 - i}★</span>
            <span className="score__bar"><span style={{ width: `${score.ratings ? (count / score.ratings) * 100 : 0}%` }} /></span>
            <span>{count}</span>
          </li>
        ))}
      </ul>
    </div>
  )
}

function Reviews({ community, isbn }: { community: Community; isbn: string }) {
  const { api } = community
  const { data } = useLoaded(async () => {
    const [score, reviews] = await Promise.all([api.bookScore(isbn), api.bookReviews(isbn)])
    return { score, reviews }
  }, `reviews:${isbn}`)
  const [more, setMore] = useState<BookReview[]>([])
  const [ended, setEnded] = useState(false)
  const [busy, setBusy] = useState(false)
  const score = data?.score ?? NO_SCORE
  const reviews = [...(data?.reviews ?? []), ...more]
  if (score.ratings === 0 && reviews.length === 0) return null
  const canShowMore = !ended && reviews.length >= BOOK_REVIEWS_PAGE && reviews.length % BOOK_REVIEWS_PAGE === 0
  const loadMore = async () => {
    setBusy(true)
    try {
      const next = await api.bookReviews(isbn, reviews.length)
      setMore((old) => [...old, ...next])
      if (next.length < BOOK_REVIEWS_PAGE) setEnded(true)
    } catch {
      setEnded(true)
    } finally {
      setBusy(false)
    }
  }
  return (
    <section className="book-community__part" aria-labelledby="book-readers">
      <h3 id="book-readers">What readers say</h3>
      {score.ratings > 0 && <ScoreBars score={score} />}
      {reviews.filter((r) => r.review).length > 0 && (
        <ul className="book-reviews">
          {reviews.filter((r) => r.review).map((r) => (
            <li key={r.person.id}>
              <div className="book-reviews__who">
                <Avatar person={r.person} size={28} />
                <PersonLink person={r.person} />
                {r.rating !== null && <span role="img" aria-label={starsLabel(r.rating) ?? undefined}>{starsText(r.rating)}</span>}
                <span className="feed-card__time">{relativeTime(r.updatedAt)}</span>
              </div>
              <p>{r.review}</p>
            </li>
          ))}
        </ul>
      )}
      {canShowMore && <button type="button" disabled={busy} onClick={() => void loadMore()}>Show more reviews</button>}
    </section>
  )
}

/** What the community says about a book. Quiet by design: nothing shows when there's nothing, or it can't be loaded. */
export function BookCommunity({ book }: { book: Book }) {
  const community = useOptionalCommunity()
  if (!community?.profile || !book.isbn) return null
  return (
    <div className="book-community" key={book.isbn}>
      <FriendsWithBook community={community} isbn={book.isbn} />
      <Reviews community={community} isbn={book.isbn} />
    </div>
  )
}
