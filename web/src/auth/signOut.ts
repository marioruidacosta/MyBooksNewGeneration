import { supabase } from '../supabase'
import { authStorageKey } from '../supabase-config'
import { raiseGuard } from './guardedStorage'
import { storedSession } from './storedSession'

/** Told to [useSession] when the stored session is dropped here, since supabase-js may be too busy to say so for a while. */
export const SIGNED_OUT_EVENT = 'mybooks:signed-out'

/** How long to give supabase-js before signing out here without it. */
const WAIT_MS = 4000

/**
 * Signs out of this browser. With an expired access token that cannot be refreshed (offline, a server that is down)
 * supabase-js keeps retrying for the better part of a minute and then gives up on signing out, returning the error and
 * keeping the session: the button would seem dead. So when it is offline, fails, or is slow, the stored session is dropped
 * here and the page told. (The server is not told then: its nightly job ends that session after 7 days without use.)
 * Only this device signs out: the account's other devices keep their own sessions.
 */
export async function signOut(): Promise<void> {
  // First of all, before anything can finish meanwhile: a refresh already retrying must not write the session back.
  raiseGuard(storedSession()?.user.id ?? null)
  if (typeof navigator === 'undefined' || navigator.onLine !== false) {
    let timer: ReturnType<typeof setTimeout> | undefined
    const outcome = await Promise.race([
      supabase.auth.signOut({ scope: 'local' }).then(({ error }) => (error ? 'failed' : 'done'), () => 'failed'),
      new Promise<string>((resolve) => {
        timer = setTimeout(() => resolve('slow'), WAIT_MS)
      }),
    ])
    clearTimeout(timer)
    if (outcome === 'done') return
  }
  try {
    window.localStorage.removeItem(authStorageKey())
  } catch {
    // Storage is unavailable: nothing was kept there.
  }
  window.dispatchEvent(new Event(SIGNED_OUT_EVENT))
  // Lets the client forget what it holds; it may be a while behind its own retries, so nothing waits for it.
  void supabase.auth.signOut({ scope: 'local' }).catch(() => {})
}
