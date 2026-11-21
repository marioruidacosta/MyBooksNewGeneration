import type { Session } from '@supabase/supabase-js'
import { authStorageKey } from '../supabase-config'

/**
 * The session supabase-js last saved in this browser, read as it is stored (without asking the server). It may hold an
 * expired access token: that is the point, it says who was signed in, and the refresh token is still there for when
 * the connection is back. Null when nothing usable is stored.
 */
export function storedSession(): Session | null {
  try {
    const raw = window.localStorage.getItem(authStorageKey())
    if (!raw) return null
    const value = JSON.parse(raw) as Partial<Session> | null
    if (!value || typeof value.refresh_token !== 'string' || typeof value.access_token !== 'string' || !value.user?.id) return null
    return value as Session
  } catch {
    return null
  }
}
