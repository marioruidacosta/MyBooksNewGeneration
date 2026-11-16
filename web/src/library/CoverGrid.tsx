import { CoverImage } from '../covers/CoverImage'
import type { Book } from '../sync/model'
import { STATUS_LABELS } from './filters'

/** The books as covers, each with its title and author, its status tab and, when rated, its stars. */
export function CoverGrid({ books, onOpen }: { books: Book[]; onOpen: (id: string) => void }) {
  const base = import.meta.env.BASE_URL
  if (books.length === 0) return null
  return (
    <ul className="cover-grid" aria-label="Covers">
      {books.map((b) => (
        <li key={b.id} className="cover-card">
          <a
            href={`${base}book/${b.id}`}
            onClick={(e) => {
              if (e.button !== 0 || e.metaKey || e.ctrlKey || e.shiftKey || e.altKey) return
              e.preventDefault()
              onOpen(b.id)
            }}
          >
            <CoverImage book={b} className="cover-card__cover" />
            <span className="cover-card__title">{b.title}</span>
            <span className="cover-card__author">{b.authors}</span>
          </a>
          <span className={`status status--${b.status.toLowerCase()}`}>{STATUS_LABELS[b.status]}</span>
          {b.rating !== null && (
            <span className="score" role="img" aria-label={`${b.rating} of 5`}>
              {'★'.repeat(b.rating)}
              <span className="score__rest">{'★'.repeat(5 - b.rating)}</span>
            </span>
          )}
        </li>
      ))}
    </ul>
  )
}
