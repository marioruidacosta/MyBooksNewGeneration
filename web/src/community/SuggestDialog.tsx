import { useState } from 'react'
import { useToast } from '../ui/toastContext'
import { communityMessage } from './api'
import { SUGGESTION_MAX_FRIENDS, SUGGESTION_NOTE_MAX, type FeedBook } from './model'
import { suggestionSentText } from './text'
import { Avatar, Dialog, LoadError } from './ui'
import { useCommunity } from './useCommunity'
import { useLoaded } from './useLoaded'

/** Choose friends, add a note if you like, and send. */
export function SuggestDialog({ book, onClose }: { book: FeedBook; onClose: () => void }) {
  const { api } = useCommunity()
  const toast = useToast()
  const { data, error, loading, reload } = useLoaded(
    async () => [...(await api.friends())].sort((a, b) => a.displayName.localeCompare(b.displayName)),
    'suggest-friends',
  )
  const [chosen, setChosen] = useState<ReadonlySet<string>>(new Set())
  const [note, setNote] = useState('')
  const [busy, setBusy] = useState(false)
  const [sendError, setSendError] = useState<string | null>(null)
  const atLimit = chosen.size >= SUGGESTION_MAX_FRIENDS

  const toggle = (id: string) => {
    setChosen((old) => {
      const next = new Set(old)
      if (!next.delete(id) && next.size < SUGGESTION_MAX_FRIENDS) next.add(id)
      return next
    })
  }

  const send = async () => {
    const asked = (data ?? []).filter((p) => chosen.has(p.id))
    setBusy(true)
    setSendError(null)
    try {
      const sent = await api.suggestBook(book, asked.map((p) => p.id), note.trim())
      toast.show(suggestionSentText(sent, asked))
      onClose()
    } catch (e) {
      setSendError(communityMessage(e))
      setBusy(false)
    }
  }

  return (
    <Dialog title="Suggest to a friend" onClose={onClose}>
      <p className="suggest-book">{book.title}</p>
      {loading && !data && <p className="loading-inline">Loading friends…</p>}
      {error && !data && <LoadError message={error} onRetry={reload} />}
      {data?.length === 0 && <p className="empty">Add friends in Community to suggest books to them.</p>}
      {data && data.length > 0 && (
        <>
          <ul className="suggest-friends">
            {data.map((p) => (
              <li key={p.id}>
                <label>
                  <input
                    type="checkbox"
                    aria-label={p.displayName}
                    checked={chosen.has(p.id)}
                    disabled={atLimit && !chosen.has(p.id)}
                    onChange={() => toggle(p.id)}
                  />
                  <Avatar person={p} size={32} />
                  <span className="suggest-friends__name">{p.displayName}</span>
                </label>
              </li>
            ))}
          </ul>
          {atLimit && <p className="field-hint">Up to {SUGGESTION_MAX_FRIENDS} friends at a time</p>}
          <label className="dialog__field">
            Note (optional)
            <textarea value={note} maxLength={SUGGESTION_NOTE_MAX} rows={2} onChange={(e) => setNote(e.target.value)} />
          </label>
        </>
      )}
      {sendError && <p className="error" role="alert">{sendError}</p>}
      <div className="dialog__actions">
        <button type="button" className="quiet" onClick={onClose}>Cancel</button>
        {data && data.length > 0 && (
          <button type="button" className="primary" disabled={busy || chosen.size === 0} onClick={() => void send()}>
            {busy ? 'Sending…' : 'Send'}
          </button>
        )}
      </div>
    </Dialog>
  )
}
