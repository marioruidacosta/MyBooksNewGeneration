import { useContext, useSyncExternalStore, type ReactNode } from 'react'
import { SharingContext } from '../share/SharingContext'
import { ShareSwitch } from './BookCommunity'
import { useOptionalCommunity } from './useCommunity'

const never = () => () => {}

/** The review with what happens to it, as on the phone: whether the book is shared, and the switch to keep it private. */
export function SharingSection({
  id, shared, review,
}: { id: string; shared: boolean | null; review: (label: string) => ReactNode }) {
  const publisher = useContext(SharingContext)
  const community = useOptionalCommunity()
  const excluded = useSyncExternalStore(publisher ? publisher.subscribe : never, () => publisher?.isExcluded(id) ?? false)
  const sharing = publisher !== null && community?.profile?.shareActivity === true
  const label = !sharing ? 'Review' : excluded ? 'Review (not shared)' : 'Review (shared with friends)'
  // Only what the label doesn't already say.
  const state = shared === null ? null
    : !sharing ? (shared ? 'Shared with friends' : 'Not shared')
    : !excluded && !shared ? 'Not shared yet'
    : null
  return (
    <fieldset className="book-section">
      <legend>{community?.profile ? 'Sharing' : 'Your review'}</legend>
      <div className="book-sharing">
        {review(label)}
        <ShareSwitch id={id} />
        {state && <p className="share-state">{state}</p>}
      </div>
    </fieldset>
  )
}
