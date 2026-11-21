import { useEffect, useState } from 'react'
import { isAuthRetryableFetchError, type Session } from '@supabase/supabase-js'
import { supabase } from '../supabase'
import { guardBlocks } from './guardedStorage'
import { SIGNED_OUT_EVENT } from './signOut'
import { storedSession } from './storedSession'

/**
 * How long to wait for supabase-js before showing the library of the account stored here. With no connection its refresh
 * of an expired token retries for about 25 s (twice over at start-up) before it gives up.
 */
const WAIT_MS = 4000

/**
 * Who is signed in. An expired access token that could not be refreshed *because of the network* (offline, a server that
 * is down) does not sign anyone out: the account stored in this browser stays, so the library opens from this device and
 * changes wait to be sent (the store and the publisher refuse to send until the session is renewed). A refresh token the
 * server rejects, or a sign-out, does sign out.
 */
export function useSession(): { session: Session | null; loading: boolean } {
  const [session, setSession] = useState<Session | null>(null)
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    let active = true
    /** Keeps the stored account (until supabase-js says otherwise); false when there is none. */
    const keepStored = (): boolean => {
      const stored = storedSession()
      if (!stored || !active || guardBlocks(stored.user.id)) return false
      setSession((current) => current ?? stored)
      setLoading(false)
      return true
    }

    // No connection at all: no point waiting for a refresh that cannot happen.
    if (typeof navigator !== 'undefined' && navigator.onLine === false) keepStored()
    const timer = setTimeout(keepStored, WAIT_MS)

    supabase.auth.getSession().then(({ data, error }) => {
      if (!active) return
      clearTimeout(timer)
      if (data.session && !guardBlocks(data.session.user.id)) {
        setSession(data.session)
      } else if (!(error && isAuthRetryableFetchError(error) && keepStored())) {
        setSession(null)
      }
      setLoading(false)
    })
    const { data } = supabase.auth.onAuthStateChange((event, next) => {
      // At start-up this reports what getSession found: no session may only mean "could not refresh", which is decided above.
      if (event === 'INITIAL_SESSION' && next === null) return
      // A late refresh or sign-in for the account that signed out does not reopen its library.
      if (next && guardBlocks(next.user.id)) return
      setSession(next)
      setLoading(false)
    })
    const onSignedOut = () => {
      setSession(null)
      setLoading(false)
    }
    window.addEventListener(SIGNED_OUT_EVENT, onSignedOut)
    return () => {
      window.removeEventListener(SIGNED_OUT_EVENT, onSignedOut)
      active = false
      clearTimeout(timer)
      data.subscription.unsubscribe()
    }
  }, [])

  return { session, loading }
}
