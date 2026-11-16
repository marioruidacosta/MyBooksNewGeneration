import { useState, useSyncExternalStore, type DragEvent } from 'react'
import { CoverImage } from '../covers/CoverImage'
import { shrinkToJpeg, uploadCover } from '../covers/coverUpload'
import { CoverContext, type CoverStorage } from '../covers/storage'
import { STATUS_LABELS } from '../library/filters'
import { supabase } from '../supabase'
import { useIsShared } from '../share/SharingContext'
import { STATUSES, type Status } from '../sync/model'
import type { LibraryStore } from '../sync/store'
import { BOOK_LIMITS, tooLongMessage } from '../sync/limits'
import { Field } from './Field'
import { dayText, parseDay, parseWhole } from './parse'
import { statusChange } from './status'
import { QuoteList } from './QuoteList'

type Props = {
  store: LibraryStore
  /** The signed-in account's id: where its cover photos are kept. */
  owner: string
  id: string
  onBack?: () => void
  /** Where cover photos go; the real storage unless a test brings its own. */
  storage?: CoverStorage
  /** Makes a picture fit the bucket; the browser's canvas unless a test brings its own. */
  shrink?: (file: Blob) => Promise<Blob>
}

const sameList = (a: string[], b: string[]) => a.length === b.length && a.every((x, i) => x === b[i])

const hasFiles = (e: DragEvent) => Array.from(e.dataTransfer?.types ?? []).includes('Files')

/** One book: every field saved as focus leaves it, status and score, quotes and the cover. */
export function BookPage({ store, owner, id, onBack, storage = supabase.storage, shrink = shrinkToJpeg }: Props) {
  const book = useSyncExternalStore(store.subscribe, () => store.book(id))
  const [coverLink, setCoverLink] = useState('')
  const [coverError, setCoverError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const shared = useIsShared(id)

  if (!book) {
    return (
      <main className="book-page">
        <p>That book isn&apos;t in your library.</p>
        {onBack && (
          <button type="button" onClick={onBack}>
            Back to the library
          </button>
        )}
      </main>
    )
  }

  const edit = (fields: Record<string, unknown>) => store.edit('books', id, fields)
  const ebook = store.ebook(id)

  /** A nullable text: empty means none. */
  const nullableText = (field: string, current: string | null) => (text: string) => {
    const next = text.trim() === '' ? null : text.trim()
    if (next !== current) edit({ [field]: next })
  }
  /** A text that is always there ('' when empty), kept as typed. */
  const plainText = (field: string, current: string) => (text: string) => {
    if (text !== current) edit({ [field]: text })
  }
  const whole = (field: string, current: number | null) => (text: string) => {
    const next = parseWhole(text)
    if (next !== undefined && next !== current) edit({ [field]: next })
  }
  const day = (field: string, current: number | null) => (text: string) => {
    const next = parseDay(text)
    if (next === undefined || dayText(next) === dayText(current)) return
    edit({ [field]: next })
  }
  const show = (n: number | null) => (n === null ? '' : String(n))

  const setCover = async (file: File | undefined) => {
    if (!file) return
    if (!file.type.startsWith('image/')) {
      setCoverError('That file is not a picture.')
      return
    }
    setCoverError(null)
    setBusy(true)
    try {
      await uploadCover(owner, id, await shrink(file), store, storage)
    } catch (e) {
      setCoverError(e instanceof Error ? e.message : 'The cover could not be uploaded.')
    } finally {
      setBusy(false)
    }
  }

  const useLink = () => {
    const link = coverLink.trim()
    if (!/^https?:\/\/\S+$/i.test(link)) {
      setCoverError('Paste a web link that starts with http:// or https://.')
      return
    }
    if (link.length > BOOK_LIMITS.cover_url) {
      setCoverError(tooLongMessage('That link', BOOK_LIMITS.cover_url))
      return
    }
    setCoverError(null)
    edit({ cover_url: link })
    setCoverLink('')
  }

  return (
    <CoverContext.Provider value={{ owner, storage }}>
      <main
        className="book-page"
        onDragOver={(e) => {
          if (hasFiles(e)) e.preventDefault()
        }}
        onDrop={(e) => {
          if (!hasFiles(e)) return
          e.preventDefault()
          void setCover(e.dataTransfer.files[0])
        }}
      >
        <header className="book-head">
          {onBack && (
            <button type="button" onClick={onBack}>
              Back to the library
            </button>
          )}
          <h1>{book.title}</h1>
          {shared !== null && <p className="share-state">{shared ? 'Shared with friends' : 'Not shared'}</p>}
        </header>

        <div className="book-layout">
          <aside className="cover-panel" aria-label="Cover">
            <CoverImage book={book} className="cover-large" />
            <label className="field">
              <span>Upload cover</span>
              <input
                type="file"
                accept="image/*"
                disabled={busy}
                onChange={(e) => {
                  const file = e.target.files?.[0]
                  e.target.value = ''
                  void setCover(file)
                }}
              />
            </label>
            <p className="hint">Or drop a picture anywhere on this page.</p>
            <label className="field">
              <span>Cover link</span>
              <input type="url" maxLength={BOOK_LIMITS.cover_url} value={coverLink} onChange={(e) => setCoverLink(e.target.value)} />
            </label>
            <div className="cover-actions">
              <button type="button" onClick={useLink} disabled={coverLink.trim() === ''}>
                Use link
              </button>
              <button type="button" className="danger" onClick={() => edit({ cover_url: null })} disabled={book.coverUrl === null}>
                Remove cover
              </button>
            </div>
            {busy && <p role="status">Uploading cover…</p>}
            {coverError && (
              <p className="error" role="alert">
                {coverError}
              </p>
            )}
          </aside>

          <form className="book-form" onSubmit={(e) => e.preventDefault()}>
            <Field
              label="Title"
              limit={BOOK_LIMITS.title}
              shown={book.title}
              onCommit={(t) => {
                // A book can't lose its title.
                if (t.trim() !== '' && t.trim() !== book.title) edit({ title: t.trim() })
              }}
            />
            <Field
              label="Authors"
              limit={BOOK_LIMITS.authors}
              shown={book.authors}
              onCommit={(t) => {
                if (t.trim() !== book.authors) edit({ authors: t.trim() })
              }}
            />
            <Field label="ISBN" limit={BOOK_LIMITS.isbn} shown={book.isbn ?? ''} onCommit={nullableText('isbn', book.isbn)} />
            <Field label="Publisher" shown={book.publisher ?? ''} onCommit={nullableText('publisher', book.publisher)} />
            <Field kind="number" label="Year" shown={show(book.publishedYear)} onCommit={whole('published_year', book.publishedYear)} />
            <Field kind="number" label="Pages" shown={show(book.pageCount)} onCommit={whole('page_count', book.pageCount)} />
            <Field label="Series" limit={BOOK_LIMITS.series} shown={book.series ?? ''} onCommit={nullableText('series', book.series)} />
            <Field label="Series number" limit={BOOK_LIMITS.series_number} shown={book.seriesNumber ?? ''} onCommit={nullableText('series_number', book.seriesNumber)} />
            <Field label="Tags" limit={BOOK_LIMITS.tags} shown={book.tags.join(', ')} onCommit={(t) => {
              const tags = [...new Set(t.split(',').map((x) => x.trim()).filter((x) => x !== ''))]
              if (!sameList(tags, book.tags)) edit({ tags })
            }} />

            <div className="field">
              <label htmlFor="book-status">Status</label>
              <select
                id="book-status"
                value={book.status}
                onChange={(e) => {
                  const status = e.target.value as Status
                  if (status !== book.status) edit(statusChange(book, status, Date.now()))
                }}
              >
                {STATUSES.map((s) => (
                  <option key={s} value={s}>
                    {STATUS_LABELS[s]}
                  </option>
                ))}
              </select>
            </div>

            <div className="field" role="group" aria-label="Score">
              <span>Score</span>
              <span className="stars">
                {[1, 2, 3, 4, 5].map((n) => (
                  <button
                    key={n}
                    type="button"
                    className="star"
                    aria-label={n === 1 ? '1 star' : `${n} stars`}
                    aria-pressed={book.rating === n}
                    onClick={() => edit({ rating: book.rating === n ? null : n })}
                  >
                    {book.rating !== null && n <= book.rating ? '★' : '☆'}
                  </button>
                ))}
              </span>
            </div>

            <Field kind="date" label="Started" shown={dayText(book.dateStarted)} onCommit={day('date_started', book.dateStarted)} />
            <Field kind="date" label="Finished" shown={dayText(book.dateRead)} onCommit={day('date_read', book.dateRead)} />
            <Field kind="number" label="Current page" shown={show(book.currentPage)} onCommit={whole('current_page', book.currentPage)} />
            <Field kind="number" label="Progress (%)" max={100} shown={show(book.progressPercent)} onCommit={whole('progress_percent', book.progressPercent)} />
            <Field label="Lent to" limit={BOOK_LIMITS.lent_to} shown={book.lentTo ?? ''} onCommit={nullableText('lent_to', book.lentTo)} />
            <Field kind="date" label="Lent on" shown={dayText(book.lentOn)} onCommit={day('lent_on', book.lentOn)} />
            <Field kind="area" label="Description" limit={BOOK_LIMITS.description} shown={book.description ?? ''} onCommit={(t) => {
              const next = t.trim() === '' ? null : t
              if (next !== book.description) edit({ description: next })
            }} />
            <Field kind="area" label="Notes" limit={BOOK_LIMITS.notes} shown={book.notes} onCommit={plainText('notes', book.notes)} />
            <Field kind="area" label="Review" limit={BOOK_LIMITS.review} shown={book.review} onCommit={plainText('review', book.review)} />
            <p className="hint">Added {new Date(book.dateAdded).toLocaleDateString()}</p>
          </form>
        </div>

        {ebook && (
          <section className="ebook" aria-labelledby="ebook-title">
            <h2 id="ebook-title">Ebook on a phone</h2>
            <p>{[ebook.fileName, ebook.format, ebook.size !== null ? `${Math.max(1, Math.round(ebook.size / 1024))} KB` : null].filter(Boolean).join(' · ') || 'Yes'}</p>
            <p className="hint">The ebook is read on the phone; the site only shows that it is there.</p>
          </section>
        )}

        <QuoteList store={store} bookId={id} />
      </main>
    </CoverContext.Provider>
  )
}
