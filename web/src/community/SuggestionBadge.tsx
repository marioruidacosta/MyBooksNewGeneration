import { useContext, useEffect, useSyncExternalStore } from 'react'
import { CommunityContext } from './useCommunity'

const never = () => () => {}

/** Looks for new suggestions when the app opens and each time it comes back. No profile call, and silent on failure. */
export function FollowSuggestions() {
  const inbox = useContext(CommunityContext)?.inbox
  useEffect(() => {
    if (!inbox) return
    const look = () => {
      if (document.visibilityState !== 'hidden') void inbox.refresh()
    }
    look()
    window.addEventListener('focus', look)
    window.addEventListener('online', look)
    document.addEventListener('visibilitychange', look)
    return () => {
      window.removeEventListener('focus', look)
      window.removeEventListener('online', look)
      document.removeEventListener('visibilitychange', look)
    }
  }, [inbox])
  return null
}

/** How many suggestions are new, on the Community link. Nothing when none are. */
export function SuggestionBadge() {
  const inbox = useContext(CommunityContext)?.inbox
  const count = useSyncExternalStore(inbox ? inbox.subscribe : never, () => inbox?.unseen() ?? 0)
  if (count === 0) return null
  return (
    <span className="nav-badge">
      <span aria-hidden="true">{count > 9 ? '9+' : count}</span>
      <span className="visually-hidden">{count === 1 ? '1 new book suggestion' : `${count} new book suggestions`}</span>
    </span>
  )
}
