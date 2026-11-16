import { useMemo, useState, useSyncExternalStore } from 'react'
import { defaultLookup, NotFoundWithErrors, type Lookup } from '../add/lookup'
import { CoverImage } from '../covers/CoverImage'
import { shrinkToJpeg, uploadCover } from '../covers/coverUpload'
import type { Book } from '../sync/model'
import type { LibraryStore } from '../sync/store'
import { catalogTitleKey, firstAuthorKey } from '../text/keys'
import { duplicateGroups, missingCover, missingIsbn, missingPages } from './duplicates'
import { fillEmpty } from './fillEmpty'
import { useToast } from '../ui/toastContext'
import { mergeBooks, planMerge, type PhotoStorage } from './mergeBooks'

type Props = {
  store: LibraryStore
  /** Whose cover photos these are. */
  owner: string
  storage: PhotoStorage
  lookup?: Lookup
  /** Makes an uploaded picture small enough; a test brings its own. */
  shrink?: (file: Blob) => Promise<Blob>
}

type TabId = 'cover' | 'pages' | 'isbn' | 'duplicates'

const quoteText = (n: number) => `${String(n)} ${n === 1 ? 'quote moves' : 'quotes move'}`

/** Books that need tidying: what is missing, and likely duplicates, each with a quick fix. */
export function TidyPage({ store, owner, storage, lookup = defaultLookup, shrink = shrinkToJpeg }: Props) {
  const books = useSyncExternalStore(store.subscribe, () => store.books())
  const toast = useToast()
  const [tab, setTab] = useState<TabId>('cover')
  const [busy, setBusy] = useState<string | null>(null)
  const [message, setMessage] = useState('')
  const [error, setError] = useState('')
  /** The kept book chosen for each group (by the group's first book), and the group being confirmed. */
  const [chosen, setChosen] = useState<Record<string, string>>({})
  const [confirming, setConfirming] = useState<string | null>(null)
  /** Books the person says are not duplicates after all: left out of their group's merge. */
  const [leftOut, setLeftOut] = useState<Set<string>>(new Set())

  const lists = useMemo(
    () => ({
      cover: missingCover(books),
      pages: missingPages(books),
      isbn: missingIsbn(books),
      duplicates: duplicateGroups(books),
    }),
    [books],
  )
  const tabs: { id: TabId; label: string; count: number }[] = [
    { id: 'cover', label: 'Missing cover', count: lists.cover.length },
    { id: 'pages', label: 'Missing pages', count: lists.pages.length },
    { id: 'isbn', label: 'Missing ISBN', count: lists.isbn.length },
    { id: 'duplicates', label: 'Possible duplicates', count: lists.duplicates.length },
  ]

  const lookUp = async (b: Book) => {
    setMessage('')
    setError('')
    setBusy(b.id)
    try {
      let found = null
      if (b.isbn) {
        found = await lookup.lookupIsbn(b.isbn)
      } else {
        const title = catalogTitleKey(b.title)
        const author = firstAuthorKey(b.authors)
        const results = await lookup.searchTitle(`${b.title} ${b.authors}`.trim())
        found =
          results.find((r) => catalogTitleKey(r.title) === title && firstAuthorKey(r.authors.join(', ')) === author) ?? null
      }
      if (!found) {
        setMessage(`No match found for ${b.title}.`)
        return
      }
      const fill = fillEmpty(store.book(b.id) ?? b, found)
      if (Object.keys(fill).length === 0) {
        setMessage(`Nothing to add to ${b.title}.`)
        return
      }
      // Like the phone's "fill in details", this isn't posted to the community.
      store.edit('books', b.id, fill, { origin: 'import' })
      setMessage(`Added to ${b.title}: ${Object.keys(fill).length} ${Object.keys(fill).length === 1 ? 'field' : 'fields'}.`)
    } catch (e) {
      setError(
        e instanceof NotFoundWithErrors
          ? "Not found. A source couldn't be reached. Try again later."
          : "Couldn't look that up just now. Try again.",
      )
    } finally {
      setBusy(null)
    }
  }

  const upload = async (b: Book, file: File | undefined) => {
    if (!file) return
    setMessage('')
    setError('')
    setBusy(b.id)
    try {
      await uploadCover(owner, b.id, await shrink(file), store, storage)
      setMessage(`Cover added to ${b.title}.`)
    } catch (e) {
      setError(e instanceof Error && e.message ? e.message : 'The cover could not be uploaded.')
    } finally {
      setBusy(null)
    }
  }

  const merge = async (keepId: string, dropIds: string[]) => {
    setConfirming(null)
    setMessage('')
    setError('')
    setBusy(keepId)
    let coverKept = false
    const undos: (() => void)[] = []
    try {
      for (const id of dropIds) {
        const keep = store.book(keepId)
        const drop = store.book(id)
        if (!keep || !drop) continue
        const r = await mergeBooks(store, keep, drop, { owner, storage })
        coverKept = coverKept || r.coverKept
        undos.push(r.undo)
      }
    } catch (e) {
      setError(e instanceof Error && e.message ? e.message : 'The books could not be merged.')
    } finally {
      setBusy(null)
      // Merges that went through can still be undone, even when a later one failed.
      if (undos.length > 0) {
        const title = store.book(keepId)?.title ?? 'the book'
        toast.show(
          `Merged into ${title}.` +
            (coverKept ? " The other book's cover photo could not be used, so the book keeps its own cover." : ''),
          // Newest first, so the kept book ends as it was before the first merge.
          { label: 'Undo', run: () => undos.reverse().forEach((u) => u()) },
        )
      }
    }
  }

  const row = (b: Book, withUpload: boolean) => (
    <li key={b.id} className="tidy-row">
      <CoverImage book={b} compact className="tidy-cover" />
      <span className="tidy-title">
        <strong>{b.title}</strong>
        <span className="hint">{b.authors}</span>
      </span>
      <button type="button" disabled={busy !== null} onClick={() => void lookUp(b)}>
        Look up
      </button>
      {withUpload && (
        <label className="tidy-upload">
          <span>Upload cover</span>
          <input
            type="file"
            accept="image/*"
            aria-label={`Upload cover for ${b.title}`}
            disabled={busy !== null}
            onChange={(e) => {
              const file = e.target.files?.[0]
              e.target.value = ''
              void upload(b, file)
            }}
          />
        </label>
      )}
    </li>
  )

  const group = (g: Book[]) => {
    const id = g[0].id
    // Only the books still checked are merged; one left out is not touched.
    const members = g.filter((b) => !leftOut.has(b.id))
    const canMerge = members.length >= 2
    const keep = members.find((b) => b.id === chosen[id]) ?? members[0] ?? g[0]
    const others = members.filter((b) => b.id !== keep.id)
    return (
      <li key={id} className="tidy-group">
        <fieldset>
          <legend>Which one do you keep?</legend>
          {g.map((b) => (
            <div key={b.id} className="tidy-member">
              <label className="tidy-choice">
                <input
                  type="radio"
                  name={`keep-${id}`}
                  checked={b.id === keep.id && !leftOut.has(b.id)}
                  disabled={busy !== null || leftOut.has(b.id)}
                  onChange={() => {
                    setChosen({ ...chosen, [id]: b.id })
                    setConfirming(null)
                  }}
                />
                <CoverImage book={b} compact className="tidy-cover" />
                <span className="tidy-title">
                  <strong>{b.title}</strong>
                  <span className="hint">
                    {b.authors}
                    {b.isbn ? ` · ${b.isbn}` : ''}
                  </span>
                </span>
              </label>
              <label className="tidy-leave">
                <input
                  type="checkbox"
                  aria-label={`Not a duplicate: ${b.title}`}
                  checked={leftOut.has(b.id)}
                  disabled={busy !== null}
                  onChange={(e) => {
                    const next = new Set(leftOut)
                    if (e.target.checked) next.add(b.id)
                    else next.delete(b.id)
                    setLeftOut(next)
                    setConfirming(null)
                  }}
                />
                <span>Not a duplicate</span>
              </label>
            </div>
          ))}
        </fieldset>
        {confirming === id && canMerge ? (
          <div role="alertdialog" aria-label="Confirm merge" className="tidy-confirm">
            <p>
              Keep <strong>{keep.title}</strong>. This will delete the other{' '}
              {others.length === 1 ? 'book' : `${String(others.length)} books`}.
            </p>
            <ul>
              {others.map((o) => {
                const plan = planMerge(store, keep, o)
                return (
                  <li key={o.id}>
                    {o.title}: {plan.changes.length > 0 ? `fills ${plan.changes.join(', ')}` : 'adds no new details'}.{' '}
                    {quoteText(plan.quotes)}.
                    {plan.photoFromDrop
                      ? plan.replacesPhoto
                        ? ' Its newer cover photo replaces the photo of the kept book.'
                        : ' Its cover photo is copied.'
                      : ''}
                    {plan.phoneData ? ' Its bookmarks and e-book on your phone are removed.' : ''}
                  </li>
                )
              })}
            </ul>
            <p>You can undo the merge for a few seconds; bookmarks, e-books and a replaced cover photo won't come back.</p>
            <button type="button" disabled={busy !== null} onClick={() => void merge(keep.id, others.map((o) => o.id))}>
              Merge and delete
            </button>
            <button type="button" onClick={() => setConfirming(null)}>
              Cancel
            </button>
          </div>
        ) : (
          <button type="button" disabled={busy !== null || !canMerge} onClick={() => setConfirming(id)}>
            Merge
          </button>
        )}
      </li>
    )
  }

  const empty = <p className="hint">Nothing to tidy here.</p>
  return (
    <main className="tidy-page">
      <h1>Tidy up</h1>
      <div role="tablist" aria-label="Lists" className="tidy-tabs">
        {tabs.map((t) => (
          <button
            key={t.id}
            type="button"
            role="tab"
            aria-selected={tab === t.id}
            onClick={() => {
              setTab(t.id)
              setConfirming(null)
            }}
          >
            {`${t.label} (${String(t.count)})`}
          </button>
        ))}
      </div>
      {busy !== null && <p className="loading">Working…</p>}
      {message && <p role="status">{message}</p>}
      {error && (
        <p className="error" role="alert">
          {error}
        </p>
      )}
      <div role="tabpanel">
        {tab === 'duplicates' ? (
          lists.duplicates.length === 0 ? (
            empty
          ) : (
            <>
              <p className="hint">These look alike. Nothing is merged unless you choose to.</p>
              <ul className="tidy-list">{lists.duplicates.map(group)}</ul>
            </>
          )
        ) : lists[tab].length === 0 ? (
          empty
        ) : (
          <ul className="tidy-list">{lists[tab].map((b) => row(b, tab === 'cover'))}</ul>
        )}
      </div>
    </main>
  )
}
