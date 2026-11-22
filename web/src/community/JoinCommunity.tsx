import { useId, useState, type FormEvent } from 'react'
import { SHARE_EXCLUSIONS_SYNCED } from '../share/publisher'
import { useOptionalStore } from '../sync/StoreContext'
import { communityMessage } from './api'
import { DeleteAccountDialog } from './DeleteAccountDialog'
import { useCommunity } from './useCommunity'
import { normalizeUsername, usernameProblem } from './text'

/** Choosing how other readers see you: the step before the community opens. */
export function JoinCommunity() {
  const { api, setProfile } = useCommunity()
  const store = useOptionalStore()
  const usernameId = useId()
  const [username, setUsername] = useState('')
  const [name, setName] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [deleting, setDeleting] = useState(false)

  const submit = async (e: FormEvent) => {
    e.preventDefault()
    const wanted = normalizeUsername(username)
    const problem = usernameProblem(wanted)
    if (problem) {
      setError(problem)
      return
    }
    if (name.trim() === '') {
      setError('Enter a name')
      return
    }
    setBusy(true)
    setError(null)
    try {
      if (!(await api.isUsernameAvailable(wanted))) {
        setError('That username is taken.')
        setBusy(false)
        return
      }
      const profile = await api.createProfile(wanted, name.trim().slice(0, 50))
      // Joining here: there are no exclusions kept on a phone from before, so the site can post this account's books.
      store?.setPref(SHARE_EXCLUSIONS_SYNCED, '1')
      setProfile(profile)
    } catch (err) {
      setError(communityMessage(err))
      setBusy(false)
    }
  }

  return (
    <main className="community-page community-join">
      <h1>Join the community</h1>
      <p className="community-lead">Follow what your friends are reading, and share your own.</p>
      <ul className="community-points">
        <li>From now on, changes to your books are shared: what you start, finish, score and review.</li>
        <li>Only friends see them unless you make your profile public.</li>
        <li>You can keep any book private, or turn sharing off, at any time.</li>
      </ul>
      <form onSubmit={(e) => void submit(e)}>
        <div className="community-join__field">
          <label htmlFor={usernameId}>Username</label>
          <span className="username-field">
            <span aria-hidden="true">@</span>
            <input
              id={usernameId}
              value={username}
              onChange={(e) => setUsername(e.target.value)}
              autoCapitalize="none"
              autoCorrect="off"
              spellCheck={false}
              autoComplete="username"
              maxLength={20}
              required
            />
          </span>
        </div>
        <label>
          Name
          <input value={name} onChange={(e) => setName(e.target.value)} maxLength={50} autoComplete="name" required />
        </label>
        {error && <p className="error" role="alert">{error}</p>}
        <button type="submit" disabled={busy}>Create profile</button>
      </form>
      <p className="community-join__leave">
        Don't want your account any more?{' '}
        <button type="button" className="quiet" onClick={() => setDeleting(true)}>Delete account</button>
      </p>
      {deleting && <DeleteAccountDialog username={null} onClose={() => setDeleting(false)} />}
    </main>
  )
}
