import { useCallback, useEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import type { CommunityApi } from './api'
import type { Profile } from './model'
import { CommunityContext, type Community, type CommunityNav } from './useCommunity'

/** The signed-in person's community account, loaded once for all the pages. */
export function CommunityProvider({ api, nav, children }: { api: CommunityApi; nav: CommunityNav; children: ReactNode }) {
  const [status, setStatus] = useState<Community['status']>('loading')
  const [profile, setProfile] = useState<Profile | null>(null)
  const [attempt, setAttempt] = useState(0)
  const wanted = useRef(false)

  useEffect(() => {
    if (attempt === 0) return
    let live = true
    setStatus('loading')
    api.myProfile().then(
      (found) => {
        if (!live) return
        setProfile(found)
        setStatus('ready')
      },
      () => {
        if (live) setStatus('error')
      },
    )
    return () => {
      live = false
    }
  }, [api, attempt])

  const reload = useCallback(() => {
    wanted.current = true
    setAttempt((n) => n + 1)
  }, [])
  // Couldn't be reached: try again as soon as the connection is back, so that the book page's switch returns.
  useEffect(() => {
    if (status !== 'error') return
    window.addEventListener('online', reload)
    return () => window.removeEventListener('online', reload)
  }, [status, reload])
  const ensureLoaded = useCallback(() => {
    if (wanted.current) return
    wanted.current = true
    setAttempt((n) => n + 1)
  }, [])
  const value = useMemo(
    () => ({ api, nav, status, profile, setProfile, reload, ensureLoaded }),
    [api, nav, status, profile, reload, ensureLoaded],
  )
  return <CommunityContext.Provider value={value}>{children}</CommunityContext.Provider>
}
