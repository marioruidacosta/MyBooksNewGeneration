import { useState } from 'react'
import { signOut } from '../auth/signOut'
import { communityMessage } from './api'
import { Dialog } from './ui'
import { useCommunity } from './useCommunity'

/** Deleting asks for the username, or the word "delete" for an account with no community profile: it can't be undone. */
export function DeleteAccountDialog({ username, onClose }: { username: string | null; onClose: () => void }) {
  const { api } = useCommunity()
  const [typed, setTyped] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const word = username ?? 'delete'
  const run = async () => {
    setBusy(true)
    setError(null)
    try {
      await api.deleteAccount()
    } catch (e) {
      setError(communityMessage(e))
      setBusy(false)
      return
    }
    await signOut()
  }
  return (
    <Dialog title="Delete your account?" onClose={onClose}>
      <p>
        {username
          ? 'This permanently deletes your account: your profile, friends, shared books and the library saved in the cloud.'
          : 'This permanently deletes your account and the library saved in the cloud.'}{' '}
        Copies already on your phones and in this browser stay there, but they will no longer sync. This can't be undone.
      </p>
      <label className="dialog__field">
        {username ? `Type your username, ${username}, to confirm` : 'Type delete to confirm'}
        <input
          value={typed}
          autoCapitalize="none"
          autoCorrect="off"
          spellCheck={false}
          onChange={(e) => setTyped(e.target.value)}
        />
      </label>
      {error && <p className="error" role="alert">{error}</p>}
      <div className="dialog__actions">
        <button type="button" className="quiet" onClick={onClose}>Cancel</button>
        <button type="button" className="danger" disabled={busy || typed.trim().toLowerCase() !== word} onClick={() => void run()}>
          Delete account
        </button>
      </div>
    </Dialog>
  )
}
