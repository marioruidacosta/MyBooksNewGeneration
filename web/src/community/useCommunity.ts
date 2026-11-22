import { createContext, useContext, useEffect } from 'react'
import type { CommunityApi } from './api'
import type { SuggestionInbox } from './inbox'
import type { Profile } from './model'

/** Where the community pages go; the app's router in real life. */
export type CommunityNav = {
  personHref: (id: string) => string
  openPerson: (id: string) => void
  openBook: (id: string) => void
  openPeople: () => void
  openAccount: () => void
  openFeed: () => void
}

export type Community = {
  api: CommunityApi
  nav: CommunityNav
  /** Books friends suggested, shared by the badge and the Community page. */
  inbox: SuggestionInbox
  status: 'loading' | 'ready' | 'error'
  /** Null until the person chooses a username. */
  profile: Profile | null
  setProfile: (profile: Profile | null) => void
  reload: () => void
  /** Loads the profile the first time a page asks for it, so a page that doesn't need it makes no call. */
  ensureLoaded: () => void
}

export const CommunityContext = createContext<Community | null>(null)

/** The community, or null outside a signed-in page. */
export function useOptionalCommunity(): Community | null {
  const community = useContext(CommunityContext)
  const ensureLoaded = community?.ensureLoaded
  useEffect(() => ensureLoaded?.(), [ensureLoaded])
  return community
}

export function useCommunity(): Community {
  const community = useOptionalCommunity()
  if (!community) throw new Error('useCommunity: no CommunityProvider')
  return community
}
