import { authStorageKey } from '../supabase-config'
import { clearExpired, markUsed } from './idle'

/**
 * Where a sign-out is remembered (localStorage, so another tab and a later launch honour it). It names the account that
 * signed out, or '*' when that is not known.
 *
 * Why: signing out offline with an expired token leaves supabase-js retrying a refresh inside its own lock. When the
 * connection returns that refresh succeeds and writes the session back, which would sign the person in again after they
 * chose to leave. The storage handed to the client refuses such a write, until the next explicit sign-in.
 */
export const GUARD_KEY = 'mybooks.auth.signedOut'

type Guard = { user: string }

function readGuard(): Guard | null {
  try {
    const raw = window.localStorage.getItem(GUARD_KEY)
    if (!raw) return null
    const value = JSON.parse(raw) as { user?: unknown }
    return { user: typeof value.user === 'string' ? value.user : '*' }
  } catch {
    return null
  }
}

/** Remembers that [user] (null when unknown) signed out: writes of that account's session are refused from now on. */
export function raiseGuard(user: string | null): void {
  try {
    window.localStorage.setItem(GUARD_KEY, JSON.stringify({ user: user ?? '*' }))
  } catch {
    // No storage: nothing is remembered, and the race is as it was.
  }
}

export function dropGuard(): void {
  try {
    window.localStorage.removeItem(GUARD_KEY)
  } catch {
    // Nothing to drop.
  }
}

/** True only while an explicit sign-in (an emailed code) is being verified: its own session is the one to store. */
let signingIn = false

/**
 * Whether a session of [userId] must be ignored: it belongs to the account that signed out (or, when not known, any).
 * Never during an explicit sign-in: supabase-js announces it before the guard is lifted, and the page must open.
 */
export function guardBlocks(userId: string | null | undefined): boolean {
  if (signingIn) return false
  const guard = readGuard()
  if (!guard) return false
  return guard.user === '*' || guard.user === userId
}

/** Runs [verify], the explicit sign-in, letting it store its session; [userOf] says whose session it made. */
export async function duringSignIn<T>(verify: () => Promise<T>, userOf: (result: T) => string | null): Promise<T> {
  // Before the session is stored: it must not be judged by the previous session's last use.
  markUsed()
  signingIn = true
  let result: T
  try {
    result = await verify()
  } finally {
    signingIn = false
  }
  const user = userOf(result)
  if (user !== null) clearExpired()
  const guard = readGuard()
  // The same account signing in again lifts the guard; another account leaves it for the one that signed out, so that
  // account's late refresh cannot replace the new session.
  if (user !== null && guard && (guard.user === '*' || guard.user === user)) dropGuard()
  return result
}

function userIdOf(value: string): string | undefined {
  try {
    const id = (JSON.parse(value) as { user?: { id?: unknown } } | null)?.user?.id
    return typeof id === 'string' ? id : undefined
  } catch {
    return undefined
  }
}

/** The storage supabase-js keeps its session in: localStorage, except for what the sign-out guard refuses. */
export const authStorage = {
  getItem: (key: string): string | null => window.localStorage.getItem(key),
  setItem: (key: string, value: string): void => {
    if (key === authStorageKey() && guardBlocks(userIdOf(value))) return
    window.localStorage.setItem(key, value)
  },
  removeItem: (key: string): void => window.localStorage.removeItem(key),
}
