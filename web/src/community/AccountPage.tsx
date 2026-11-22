import { useContext, useRef, useState } from 'react'
import { shrinkToJpeg } from '../covers/coverUpload'
import { SharingContext } from '../share/SharingContext'
import { useToast } from '../ui/toastContext'
import { communityMessage } from './api'
import { DeleteAccountDialog } from './DeleteAccountDialog'
import { useCommunity } from './useCommunity'
import { NeedsProfile } from './CommunityPage'
import type { Profile, Visibility } from './model'
import { Avatar, ConfirmDialog, LoadError, PersonRow } from './ui'
import { useLoaded } from './useLoaded'

/** The largest side of a profile photo, as on the phone. */
const AVATAR_SIDE = 512
const AVATAR_BYTES = 1_000_000

function Account({ profile }: { profile: Profile }) {
  const { api, nav, setProfile } = useCommunity()
  const toast = useToast()
  const [name, setName] = useState(profile.displayName)
  const [busy, setBusy] = useState(false)
  const [dialog, setDialog] = useState<'stop' | 'delete' | null>(null)
  const photo = useRef<HTMLInputElement>(null)
  const blocked = useLoaded(() => api.blockedPeople(), 'blocked')
  const publisher = useContext(SharingContext)
  const setSharing = async (enabled: boolean) => {
    const updated = await api.setShareActivity(enabled)
    setProfile(updated)
    void publisher?.refresh()
    return updated
  }

  const save = async (call: () => Promise<Profile>, done?: string) => {
    setBusy(true)
    try {
      setProfile(await call())
      if (done) toast.show(done)
    } catch (e) {
      toast.show(communityMessage(e))
    } finally {
      setBusy(false)
    }
  }
  const saveName = () => {
    const trimmed = name.trim()
    if (trimmed === '') {
      toast.show('Enter a name')
      return
    }
    void save(() => api.updateProfile(trimmed.slice(0, 50), profile.visibility), 'Name saved')
  }
  const setVisibility = (visibility: Visibility) => {
    if (visibility !== profile.visibility) void save(() => api.updateProfile(profile.displayName, visibility))
  }
  const choosePhoto = async (file: File | undefined) => {
    if (!file) return
    setBusy(true)
    try {
      const jpeg = await shrinkToJpeg(file, AVATAR_SIDE, AVATAR_BYTES)
      setProfile(await api.uploadAvatar(jpeg))
    } catch (e) {
      toast.show(e instanceof Error && e.name === 'CommunityError' ? communityMessage(e) : "That photo couldn't be used.")
    } finally {
      setBusy(false)
      if (photo.current) photo.current.value = ''
    }
  }

  return (
    <main className="community-page account-page">
      <button type="button" className="back-link" onClick={nav.openFeed}>Back to the community</button>
      <h1>Your profile</h1>
      <div className="account-photo">
        <Avatar person={profile} size={72} />
        <div>
          <p className="person-head__username">@{profile.username}</p>
          <button type="button" disabled={busy} onClick={() => photo.current?.click()}>Change photo</button>
          <input
            ref={photo}
            type="file"
            accept="image/*"
            hidden
            aria-label="Choose a profile photo"
            onChange={(e) => void choosePhoto(e.target.files?.[0])}
          />
        </div>
      </div>

      <section aria-labelledby="account-name">
        <h2 id="account-name">Name</h2>
        <div className="account-row">
          <input aria-labelledby="account-name" value={name} maxLength={50} autoComplete="name" onChange={(e) => setName(e.target.value)} />
          <button type="button" disabled={busy || name.trim() === profile.displayName} onClick={saveName}>Save</button>
        </div>
      </section>

      <section>
        <fieldset className="account-choice" disabled={busy}>
          <legend>Who can see your reading activity</legend>
          <label>
            <input type="radio" name="visibility" checked={profile.visibility === 'friends'} onChange={() => setVisibility('friends')} />
            Friends only
          </label>
          <label>
            <input type="radio" name="visibility" checked={profile.visibility === 'public'} onChange={() => setVisibility('public')} />
            Everyone
          </label>
        </fieldset>
      </section>

      <section aria-labelledby="account-sharing">
        <h2 id="account-sharing">Share my reading activity</h2>
        <p className="community-note">
          {profile.shareActivity
            ? 'Changes to your books are shared automatically. You can keep a single book private from its page.'
            : 'Nothing is being shared. Turning this on shares your changes from now on.'}
        </p>
        <label className="switch-row">
          <input
            type="checkbox"
            role="switch"
            checked={profile.shareActivity}
            disabled={busy}
            onChange={(e) => {
              if (e.target.checked) void save(() => setSharing(true))
              else setDialog('stop')
            }}
          />
          Share my reading activity
        </label>
      </section>

      <section aria-labelledby="account-blocked">
        <h2 id="account-blocked">Blocked people</h2>
        {blocked.error && <LoadError message={blocked.error} onRetry={blocked.reload} />}
        {blocked.data?.length === 0 && <p className="community-note">Nobody.</p>}
        {blocked.data && blocked.data.length > 0 && (
          <ul className="person-list">
            {blocked.data.map((p) => (
              <PersonRow key={p.id} person={p}>
                <button
                  type="button"
                  aria-label={`Unblock ${p.displayName}`}
                  onClick={() => {
                    api.unblock(p.id).then(
                      () => blocked.setData((old) => old?.filter((x) => x.id !== p.id) ?? null),
                      (e: unknown) => toast.show(communityMessage(e)),
                    )
                  }}
                >
                  Unblock
                </button>
              </PersonRow>
            ))}
          </ul>
        )}
      </section>

      <section className="account-danger">
        <button type="button" className="danger" onClick={() => setDialog('delete')}>Delete account</button>
      </section>

      {dialog === 'stop' && (
        <ConfirmDialog
          title="Stop sharing?"
          body="This removes everything you've shared from the community."
          confirm="Stop sharing"
          danger
          onConfirm={async () => { await setSharing(false) }}
          onClose={() => setDialog(null)}
        />
      )}
      {dialog === 'delete' && <DeleteAccountDialog username={profile.username} onClose={() => setDialog(null)} />}
    </main>
  )
}

export function AccountPage() {
  return (
    <NeedsProfile>
      <AccountWithProfile />
    </NeedsProfile>
  )
}

function AccountWithProfile() {
  const { profile } = useCommunity()
  return profile ? <Account profile={profile} /> : null
}
