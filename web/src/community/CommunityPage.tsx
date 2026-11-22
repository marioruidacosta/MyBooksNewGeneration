import { useState, type ReactNode } from 'react'
import { useCommunity } from './useCommunity'
import { FeedList } from './FeedCard'
import { JoinCommunity } from './JoinCommunity'
import { SuggestedToYou } from './SuggestedToYou'
import type { FeedScope, FriendReading } from './model'
import { CommunityCover, Avatar, LoadError } from './ui'
import { useLoaded } from './useLoaded'
import { useFeed } from './useFeed'

const SCOPE_KEY = 'mybooks.community.scope'

function loadScope(): FeedScope {
  try {
    return window.localStorage.getItem(SCOPE_KEY) === 'everyone' ? 'everyone' : 'friends'
  } catch {
    return 'friends'
  }
}

/** Shows [children] once the person's community account is known, and asks them to join if they have none. */
export function NeedsProfile({ children }: { children: ReactNode }) {
  const { status, profile, reload } = useCommunity()
  if (status === 'loading') return <p className="loading">Loading…</p>
  if (status === 'error') {
    return (
      <main className="community-page">
        <h1>Community</h1>
        <LoadError message="The community couldn't be reached. Your library still works." onRetry={reload} />
      </main>
    )
  }
  return profile ? <>{children}</> : <JoinCommunity />
}

function ReadingCard({ item }: { item: FriendReading }) {
  const { nav } = useCommunity()
  return (
    <li className="friends-reading__item">
      <button
        type="button"
        className="friends-reading__open"
        aria-label={`${item.person.displayName} is reading ${item.book.title}`}
        onClick={() => nav.openPerson(item.person.id)}
      >
        <span className="friends-reading__cover">
          <CommunityCover title={item.book.title} authors={item.book.authors} url={item.book.coverUrl} />
          <span className="friends-reading__face"><Avatar person={item.person} size={26} /></span>
        </span>
        {item.progress !== null && (
          <span className="feed-progress" aria-hidden="true"><span style={{ width: `${item.progress}%` }} /></span>
        )}
        <span className="friends-reading__who">
          <span className="friends-reading__name">{item.person.displayName}</span>
          {item.progress !== null && <span className="friends-reading__percent">· {item.progress}%</span>}
        </span>
      </button>
    </li>
  )
}

/** What friends are reading now. Nothing at all when there's nothing, or it couldn't be loaded. */
function FriendsReadingRow() {
  const { api } = useCommunity()
  const { data } = useLoaded(() => api.friendsReading(), 'friends-reading')
  if (!data || data.length === 0) return null
  return (
    <section className="friends-reading" aria-labelledby="friends-reading-title">
      <h2 id="friends-reading-title">Friends are reading</h2>
      <ul>{data.map((r) => <ReadingCard key={`${r.person.id}:${r.book.shareId}`} item={r} />)}</ul>
    </section>
  )
}

function Feed() {
  const { api, nav } = useCommunity()
  const [scope, setScope] = useState<FeedScope>(loadScope)
  const feed = useFeed((cursor) => api.feed(scope, cursor), scope)
  const { data: requests } = useLoaded(() => api.incomingRequests(), 'requests')
  const choose = (next: FeedScope) => {
    setScope(next)
    try {
      window.localStorage.setItem(SCOPE_KEY, next)
    } catch {
      // The choice just isn't kept.
    }
  }
  const waiting = requests?.length ?? 0
  return (
    <main className="community-page">
      <div className="community-head">
        <h1>Community</h1>
        <div className="community-head__links">
          <button type="button" onClick={nav.openPeople}>
            People{waiting > 0 ? ` (${waiting} waiting)` : ''}
          </button>
          <button type="button" onClick={nav.openAccount}>Your profile</button>
        </div>
      </div>
      <div className="community-tabs" role="group" aria-label="Whose updates">
        <button type="button" className="chip" aria-pressed={scope === 'friends'} onClick={() => choose('friends')}>Friends</button>
        <button type="button" className="chip" aria-pressed={scope === 'everyone'} onClick={() => choose('everyone')}>Everyone</button>
      </div>
      <SuggestedToYou />
      {scope === 'friends' && <FriendsReadingRow />}
      {feed.loading && <p className="loading-inline">Loading…</p>}
      {!feed.loading && feed.count === 0 && !feed.error && (
        <p className="empty">
          {scope === 'friends' ? 'Nothing yet. Add friends to see what they are reading.' : 'Nobody has shared anything yet.'}
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

export function CommunityPage() {
  return <NeedsProfile><Feed /></NeedsProfile>
}
