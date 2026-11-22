import { useEffect, useState } from 'react'
import { useToast } from '../ui/toastContext'
import { communityMessage } from './api'
import { useCommunity } from './useCommunity'
import { NeedsProfile } from './CommunityPage'
import type { Person, Relationship, SearchResult } from './model'
import { suggestionText } from './text'
import { ConfirmDialog, LoadError, PersonRow } from './ui'
import { useLoaded } from './useLoaded'

const SEARCH_WAIT_MS = 300

/** The button for how you relate to someone: add, cancel, accept. Friends are removed from their own list. */
function RelationshipAction({
  person, relationship, onChange,
}: { person: Person; relationship: Relationship; onChange: (r: Relationship) => void }) {
  const { api } = useCommunity()
  const toast = useToast()
  const [busy, setBusy] = useState(false)
  const act = async (call: () => Promise<Relationship>) => {
    setBusy(true)
    try {
      onChange(await call())
    } catch (e) {
      toast.show(communityMessage(e))
    } finally {
      setBusy(false)
    }
  }
  const named = (verb: string) => `${verb} ${person.displayName}`
  switch (relationship) {
    case 'friends':
      return <span className="person-state">Friends</span>
    case 'sent':
      return (
        <button type="button" disabled={busy} aria-label={`Cancel request to ${person.displayName}`} onClick={() => void act(async () => {
          await api.cancelRequest(person.id)
          return 'none'
        })}>Cancel request</button>
      )
    case 'received':
      return (
        <button type="button" className="primary" disabled={busy} aria-label={named('Accept')} onClick={() => void act(async () => {
          await api.respond(person.id, true)
          return 'friends'
        })}>Accept</button>
      )
    case 'none':
      return (
        <button type="button" className="primary" disabled={busy} aria-label={named('Add')} onClick={() => void act(() => api.sendRequest(person.id))}>
          Add friend
        </button>
      )
  }
}

function Search({ onFriendsChanged }: { onFriendsChanged: () => void }) {
  const { api } = useCommunity()
  const [query, setQuery] = useState('')
  const [results, setResults] = useState<SearchResult[] | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    const q = query.trim()
    setError(null)
    if (q.length < 2) {
      setResults(null)
      return
    }
    let live = true
    const timer = setTimeout(() => {
      api.search(q).then(
        (found) => {
          if (live) setResults(found)
        },
        (e: unknown) => {
          if (live) setError(communityMessage(e))
        },
      )
    }, SEARCH_WAIT_MS)
    return () => {
      live = false
      clearTimeout(timer)
    }
  }, [api, query])

  return (
    <section aria-labelledby="people-search">
      <h2 id="people-search">Find readers</h2>
      <input
        type="search"
        className="people-search"
        aria-labelledby="people-search"
        placeholder="Name or @username"
        value={query}
        autoCapitalize="none"
        onChange={(e) => setQuery(e.target.value)}
      />
      {error && <p className="error" role="alert">{error}</p>}
      {results?.length === 0 && <p className="community-note">Nobody found.</p>}
      {results && results.length > 0 && (
        <ul className="person-list">
          {results.map((r) => (
            <PersonRow key={r.person.id} person={r.person}>
              <RelationshipAction
                person={r.person}
                relationship={r.relationship}
                onChange={(relationship) => {
                  setResults((old) => old?.map((x) => (x.person.id === r.person.id ? { ...x, relationship } : x)) ?? null)
                  onFriendsChanged()
                }}
              />
            </PersonRow>
          ))}
        </ul>
      )}
    </section>
  )
}

function People() {
  const { api, nav } = useCommunity()
  const toast = useToast()
  const lists = useLoaded(async () => {
    const [friends, incoming, outgoing] = await Promise.all([api.friends(), api.incomingRequests(), api.outgoingRequests()])
    return { friends, incoming, outgoing }
  }, 'people')
  const suggested = useLoaded(() => api.suggestedReaders(), 'suggested')
  const [removing, setRemoving] = useState<Person | null>(null)
  const [busy, setBusy] = useState(false)
  const reload = lists.reload

  const answer = async (person: Person, accept: boolean) => {
    setBusy(true)
    try {
      await api.respond(person.id, accept)
      reload()
    } catch (e) {
      toast.show(communityMessage(e))
    } finally {
      setBusy(false)
    }
  }
  const cancel = async (person: Person) => {
    setBusy(true)
    try {
      await api.cancelRequest(person.id)
      reload()
    } catch (e) {
      toast.show(communityMessage(e))
    } finally {
      setBusy(false)
    }
  }

  const data = lists.data
  const known = new Set([...(data?.friends ?? []), ...(data?.incoming ?? []), ...(data?.outgoing ?? [])].map((p) => p.id))
  const readers = (suggested.data ?? []).filter((s) => !known.has(s.person.id))

  return (
    <main className="community-page">
      <button type="button" className="back-link" onClick={nav.openFeed}>Back to the community</button>
      <h1>People</h1>
      <Search onFriendsChanged={reload} />
      {lists.error && <LoadError message={lists.error} onRetry={reload} />}
      {data && data.incoming.length > 0 && (
        <section aria-labelledby="people-requests">
          <h2 id="people-requests">Friend requests</h2>
          <ul className="person-list">
            {data.incoming.map((p) => (
              <PersonRow key={p.id} person={p}>
                <button type="button" className="primary" disabled={busy} aria-label={`Accept ${p.displayName}`} onClick={() => void answer(p, true)}>Accept</button>
                <button type="button" disabled={busy} aria-label={`Decline ${p.displayName}`} onClick={() => void answer(p, false)}>Decline</button>
              </PersonRow>
            ))}
          </ul>
        </section>
      )}
      {readers.length > 0 && (
        <section aria-labelledby="people-suggested">
          <h2 id="people-suggested">Readers like you</h2>
          <ul className="person-list">
            {readers.map((s) => (
              <PersonRow key={s.person.id} person={s.person} detail={suggestionText(s) || `@${s.person.username}`}>
                <RelationshipAction
                  person={s.person}
                  relationship="none"
                  onChange={() => {
                    suggested.setData((old) => old?.filter((x) => x.person.id !== s.person.id) ?? null)
                    reload()
                  }}
                />
              </PersonRow>
            ))}
          </ul>
        </section>
      )}
      {data && (
        <section aria-labelledby="people-friends">
          <h2 id="people-friends">Friends</h2>
          {data.friends.length === 0 ? (
            <p className="community-note">No friends yet. Search for someone above.</p>
          ) : (
            <ul className="person-list">
              {data.friends.map((p) => (
                <PersonRow key={p.id} person={p}>
                  <button type="button" className="quiet" aria-label={`Remove ${p.displayName}`} onClick={() => setRemoving(p)}>Remove</button>
                </PersonRow>
              ))}
            </ul>
          )}
        </section>
      )}
      {data && data.outgoing.length > 0 && (
        <section aria-labelledby="people-sent">
          <h2 id="people-sent">Requests you sent</h2>
          <ul className="person-list">
            {data.outgoing.map((p) => (
              <PersonRow key={p.id} person={p}>
                <button type="button" disabled={busy} aria-label={`Cancel request to ${p.displayName}`} onClick={() => void cancel(p)}>Cancel</button>
              </PersonRow>
            ))}
          </ul>
        </section>
      )}
      {removing && (
        <ConfirmDialog
          title={`Remove ${removing.displayName}?`}
          body="You'll stop seeing each other's friends-only updates."
          confirm="Remove"
          danger
          onConfirm={async () => {
            await api.removeFriend(removing.id)
            reload()
          }}
          onClose={() => setRemoving(null)}
        />
      )}
    </main>
  )
}

export function PeoplePage() {
  return <NeedsProfile><People /></NeedsProfile>
}
