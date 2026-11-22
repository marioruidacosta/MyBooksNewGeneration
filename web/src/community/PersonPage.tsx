import { useState } from 'react'
import { useToast } from '../ui/toastContext'
import { communityMessage } from './api'
import { useCommunity } from './useCommunity'
import { NeedsProfile } from './CommunityPage'
import { FeedList, ReportDialog } from './FeedCard'
import type { Person, Relationship } from './model'
import { Avatar, ConfirmDialog, LoadError } from './ui'
import { useLoaded } from './useLoaded'
import { useFeed } from './useFeed'

function Actions({
  person, relationship, onChange, onBlocked,
}: { person: Person; relationship: Relationship; onChange: () => void; onBlocked: () => void }) {
  const { api } = useCommunity()
  const toast = useToast()
  const [busy, setBusy] = useState(false)
  const [dialog, setDialog] = useState<'remove' | 'block' | 'report' | null>(null)
  const act = async (call: () => Promise<unknown>) => {
    setBusy(true)
    try {
      await call()
      onChange()
    } catch (e) {
      toast.show(communityMessage(e))
    } finally {
      setBusy(false)
    }
  }
  return (
    <div className="person-actions">
      {relationship === 'none' && (
        <button type="button" className="primary" disabled={busy} onClick={() => void act(() => api.sendRequest(person.id))}>Add friend</button>
      )}
      {relationship === 'sent' && (
        <button type="button" disabled={busy} onClick={() => void act(() => api.cancelRequest(person.id))}>Cancel request</button>
      )}
      {relationship === 'received' && (
        <>
          <button type="button" className="primary" disabled={busy} onClick={() => void act(() => api.respond(person.id, true))}>Accept</button>
          <button type="button" disabled={busy} onClick={() => void act(() => api.respond(person.id, false))}>Decline</button>
        </>
      )}
      {relationship === 'friends' && <button type="button" onClick={() => setDialog('remove')}>Remove friend</button>}
      <button type="button" className="quiet" onClick={() => setDialog('report')}>Report</button>
      <button type="button" className="quiet" onClick={() => setDialog('block')}>Block</button>
      {dialog === 'remove' && (
        <ConfirmDialog
          title={`Remove ${person.displayName}?`}
          body="You'll stop seeing each other's friends-only updates."
          confirm="Remove"
          danger
          onConfirm={async () => {
            await api.removeFriend(person.id)
            onChange()
          }}
          onClose={() => setDialog(null)}
        />
      )}
      {dialog === 'block' && (
        <ConfirmDialog
          title={`Block ${person.displayName}?`}
          body="You won't see each other's updates or profiles, and any friendship ends. You can unblock them from your profile."
          confirm="Block"
          danger
          onConfirm={async () => {
            await api.block(person.id)
            onBlocked()
          }}
          onClose={() => setDialog(null)}
        />
      )}
      {dialog === 'report' && <ReportDialog person={person} onClose={() => setDialog(null)} />}
    </div>
  )
}

function PersonView({ id }: { id: string }) {
  const { api, nav } = useCommunity()
  const loaded = useLoaded(async () => {
    const [person, relationship] = await Promise.all([api.person(id), id === api.me ? Promise.resolve<Relationship>('none') : api.relationship(id)])
    return { person, relationship }
  }, id)
  const feed = useFeed((cursor) => api.personActivity(id, cursor), id)
  const back = <button type="button" className="back-link" onClick={nav.openFeed}>Back to the community</button>

  if (loaded.loading && !loaded.data) return <p className="loading">Loading…</p>
  if (loaded.error && !loaded.data) {
    return <main className="community-page">{back}<LoadError message={loaded.error} onRetry={loaded.reload} /></main>
  }
  const person = loaded.data?.person
  if (!person) {
    return <main className="community-page">{back}<h1>Not available</h1><p className="empty">This profile isn't available.</p></main>
  }
  const mine = id === api.me
  return (
    <main className="community-page">
      {back}
      <header className="person-head">
        <Avatar person={person} size={72} />
        <div>
          <h1>{person.displayName}</h1>
          <p className="person-head__username">@{person.username}</p>
        </div>
      </header>
      {mine ? (
        <button type="button" onClick={nav.openAccount}>Edit your profile</button>
      ) : (
        <Actions
          person={person}
          relationship={loaded.data?.relationship ?? 'none'}
          onChange={() => {
            loaded.reload()
            feed.reload()
          }}
          onBlocked={nav.openFeed}
        />
      )}
      <h2 className="person-activity">Activity</h2>
      {feed.loading && <p className="loading-inline">Loading…</p>}
      {!feed.loading && feed.count === 0 && !feed.error && (
        <p className="empty">
          {mine || person.visibility === 'public' || loaded.data?.relationship === 'friends'
            ? 'Nothing shared yet.'
            : 'Only friends can see what they are reading.'}
        </p>
      )}
      {feed.count > 0 && <FeedList items={feed.items} onChange={feed.change} />}
      {feed.error && <LoadError message={feed.error} onRetry={feed.count > 0 ? feed.more : feed.reload} />}
      {feed.hasMore && !feed.error && (
        <button type="button" className="community-more" disabled={feed.loadingMore} onClick={feed.more}>
          {feed.loadingMore ? 'Loading…' : 'Show more'}
        </button>
      )}
    </main>
  )
}

export function PersonPage({ id }: { id: string }) {
  return <NeedsProfile><PersonView key={id} id={id} /></NeedsProfile>
}
