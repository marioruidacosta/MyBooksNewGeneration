import { useEffect, useState, useSyncExternalStore, type CSSProperties, type DragEvent } from 'react'
import { coverColorCss } from '../covers/coverColor'
import { CoverImage } from '../covers/CoverImage'
import { shrinkToJpeg, uploadCover } from '../covers/coverUpload'
import { CoverContext, type CoverStorage } from '../covers/storage'
import { allTags, STATUS_LABELS } from '../library/filters'
import { supabase } from '../supabase'
import { useIsShared } from '../share/SharingContext'
import { STATUSES, type Status } from '../sync/model'
import type { LibraryStore } from '../sync/store'
import { BOOK_LIMITS, tooLongMessage } from '../sync/limits'
import { formatDay } from '../text/dates'
import { AtAGlance } from './AtAGlance'
import { DateField } from './DateField'
import { Field } from './Field'
import { parseWhole } from './parse'
import { progressChange } from './progress'
import { statusChange } from './status'
import { QuoteList } from './QuoteList'
import { TagInput } from './TagInput'

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

/** Pasting a picture anywhere but in a text box sets it as the cover. */
function PasteCover({ onFile }: { onFile: (file: File) => void }) {
  useEffect(() => {
    const onPaste = (e: ClipboardEvent) => {
      const target = e.target
      if (target instanceof HTMLElement && (target.isContentEditable || /^(INPUT|TEXTAREA|SELECT)$/.test(target.tagName))) return
      const file = Array.from(e.clipboardData?.files ?? []).find((f) => f.type.startsWith('image/'))
      if (!file) return
      e.preventDefault()
      onFile(file)
    }
    document.addEventListener('paste', onPaste)
    return () => document.removeEventListener('paste', onPaste)
  }, [onFile])
  return null
}

const hasFiles = (e: DragEvent) => Array.from(e.dataTransfer?.types ?? []).includes('Files')

type Ebook = NonNullable<ReturnType<LibraryStore['ebook']>>

/** What the phone has for the ebook, in a line. */
const ebookLine = (e: Ebook) =>
  [e.fileName, e.format, e.size !== null ? `${String(Math.max(1, Math.round(e.size / 1024)))} KB` : null].filter(Boolean).join(', ') || 'Yes'

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
  const suggestions = allTags(store.books())

  /** A nullable text: empty means none. */
  const nullableText = (field: string, current: string | null) => (text: string) => {
    const next = text.trim() === '' ? null : text.trim()
    if (next === current) return false
    edit({ [field]: next })
  }
  /** A text that is always there ('' when empty), kept as typed. */
  const plainText = (field: string, current: string) => (text: string) => {
    if (text === current) return false
    edit({ [field]: text })
  }
  const whole = (field: string, current: number | null) => (text: string) => {
    const next = parseWhole(text)
    if (next === undefined || next === current) return false
    edit({ [field]: next })
  }
  const day = (field: string) => (next: number | null) => {
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

  const facts = [
    book.publisher,
    book.publishedYear !== null ? String(book.publishedYear) : null,
    book.pageCount !== null ? `${book.pageCount} pages` : null,
  ].filter((f): f is string => f !== null && f !== '')

  return (
    <CoverContext.Provider value={{ owner, storage }}>
      <main
        className="book-page"
        style={{ '--cloth': coverColorCss(book.title) } as CSSProperties}
        onDragOver={(e) => {
          if (hasFiles(e)) e.preventDefault()
        }}
        onDrop={(e) => {
          if (!hasFiles(e)) return
          e.preventDefault()
          void setCover(e.dataTransfer.files[0])
        }}
      >
        <PasteCover onFile={(file) => void setCover(file)} />
        <header className="book-hero">
          <div className="book-hero__inner">
            {onBack && (
              <button type="button" className="back" onClick={onBack}>
                Back to the library
              </button>
            )}
            <CoverImage book={book} className="cover-large" />
            <div className="book-hero__text">
              <h1>{book.title}</h1>
              {book.authors !== '' && <p className="book-hero__author">{book.authors}</p>}
              {facts.length > 0 && <p className="book-hero__facts">{facts.join(', ')}</p>}
              <div className="book-hero__state">
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
                        className={`star ${book.rating !== null && n <= book.rating ? 'star--on' : ''}`}
                        aria-label={n === 1 ? '1 star' : `${n} stars`}
                        aria-pressed={book.rating === n}
                        onClick={() => edit({ rating: book.rating === n ? null : n })}
                      >
                        ★
                      </button>
                    ))}
                  </span>
                </div>
              </div>
            </div>
          </div>
        </header>

        <div className="book-body">
          <AtAGlance
            book={book}
            shared={shared}
            ebook={ebook ? ebookLine(ebook) : null}
            onMarkRead={() => edit(statusChange(book, 'READ', Date.now()))}
            onProgress={(input) => edit(progressChange(book, input))}
          />
          <div className="book-main">
          <form className="book-form" onSubmit={(e) => e.preventDefault()}>
            <fieldset className="book-section">
              <legend>Reading</legend>
              <div className="book-fields">
                <DateField label="Started" value={book.dateStarted} onCommit={day('date_started')} />
                <DateField label="Finished" value={book.dateRead} onCommit={day('date_read')} />
                <Field kind="number" label="Current page" shown={show(book.currentPage)} onCommit={whole('current_page', book.currentPage)} />
                <Field kind="number" label="Progress (%)" max={100} shown={show(book.progressPercent)} onCommit={whole('progress_percent', book.progressPercent)} />
              </div>
            </fieldset>

            <fieldset className="book-section">
              <legend>Your words</legend>
              <div className="book-fields">
                <Field kind="area" label="Review" limit={BOOK_LIMITS.review} shown={book.review} onCommit={plainText('review', book.review)} />
                <Field kind="area" label="Notes" limit={BOOK_LIMITS.notes} shown={book.notes} onCommit={plainText('notes', book.notes)} />
              </div>
            </fieldset>

            <fieldset className="book-section">
              <legend>About the book</legend>
              <div className="book-fields">
                <Field
                  label="Title"
                  limit={BOOK_LIMITS.title}
                  shown={book.title}
                  onCommit={(t) => {
                    // A book can't lose its title.
                    if (t.trim() === '' || t.trim() === book.title) return false
                    edit({ title: t.trim() })
                  }}
                />
                <Field
                  label="Authors"
                  limit={BOOK_LIMITS.authors}
                  shown={book.authors}
                  onCommit={(t) => {
                    if (t.trim() === book.authors) return false
                    edit({ authors: t.trim() })
                  }}
                />
                <Field label="ISBN" limit={BOOK_LIMITS.isbn} shown={book.isbn ?? ''} onCommit={nullableText('isbn', book.isbn)} />
                <Field label="Publisher" shown={book.publisher ?? ''} onCommit={nullableText('publisher', book.publisher)} />
                <Field kind="number" label="Year" shown={show(book.publishedYear)} onCommit={whole('published_year', book.publishedYear)} />
                <Field kind="number" label="Pages" shown={show(book.pageCount)} onCommit={whole('page_count', book.pageCount)} />
                <Field label="Series" limit={BOOK_LIMITS.series} shown={book.series ?? ''} onCommit={nullableText('series', book.series)} />
                <Field label="Series number" limit={BOOK_LIMITS.series_number} shown={book.seriesNumber ?? ''} onCommit={nullableText('series_number', book.seriesNumber)} />
                <TagInput label="Tags" tags={book.tags} suggestions={suggestions} onChange={(tags) => edit({ tags })} />
                <Field kind="area" label="Description" limit={BOOK_LIMITS.description} shown={book.description ?? ''} onCommit={(t) => {
                  const next = t.trim() === '' ? null : t
                  if (next === book.description) return false
                  edit({ description: next })
                }} />
              </div>
            </fieldset>

            <fieldset className="book-section">
              <legend>Lending</legend>
              <div className="book-fields">
                <Field label="Lent to" limit={BOOK_LIMITS.lent_to} shown={book.lentTo ?? ''} onCommit={nullableText('lent_to', book.lentTo)} />
                <DateField label="Lent on" value={book.lentOn} onCommit={day('lent_on')} />
              </div>
            </fieldset>
          </form>

          <QuoteList store={store} bookId={id} />

          <section className="book-section cover-panel" aria-label="Cover">
            <h2>Cover</h2>
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
            <p className="hint">Or drop or paste a picture anywhere on this page.</p>
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
          </section>

          {ebook && (
            <section className="book-section ebook" aria-labelledby="ebook-title">
              <h2 id="ebook-title">Ebook on a phone</h2>
              <p>{ebookLine(ebook)}</p>
              <p className="hint">The ebook is read on the phone; the site only shows that it is there.</p>
            </section>
          )}

          <p className="hint added">Added {formatDay(book.dateAdded)}</p>
          </div>
        </div>
      </main>
    </CoverContext.Provider>
  )
}
