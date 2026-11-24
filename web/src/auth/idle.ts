/**
 * Each device keeps its own session, for as long as it is used: after [IDLE_LIMIT_MS] without use it asks for a new
 * sign-in. (A job on the server also ends sessions that stopped refreshing, for devices that never come back.)
 */
export const IDLE_LIMIT_MS = 7 * 24 * 60 * 60 * 1000

/** When this device last used the app. */
export const LAST_USED_KEY = 'mybooks.auth.lastUsed'
/** Set when the session ended for lack of use, so that the sign-in page can say why. */
export const EXPIRED_KEY = 'mybooks.auth.expired'

export function markUsed(now: number = Date.now()): void {
  try {
    window.localStorage.setItem(LAST_USED_KEY, String(now))
  } catch {
    // No storage: the session then has no limit on this device, and the server's job still ends it.
  }
}

/** Never true without a recorded use (a session from before this version starts counting now) or with a clock moved back. */
export function isIdle(now: number = Date.now()): boolean {
  try {
    const last = Number(window.localStorage.getItem(LAST_USED_KEY))
    return Number.isFinite(last) && last > 0 && last <= now && now - last > IDLE_LIMIT_MS
  } catch {
    return false
  }
}

export function noteExpired(): void {
  try {
    window.localStorage.setItem(EXPIRED_KEY, '1')
  } catch {
    // The sign-in page just won't say why.
  }
}

export function wasExpired(): boolean {
  try {
    return window.localStorage.getItem(EXPIRED_KEY) === '1'
  } catch {
    return false
  }
}

export function clearExpired(): void {
  try {
    window.localStorage.removeItem(EXPIRED_KEY)
  } catch {
    // Nothing kept.
  }
}
