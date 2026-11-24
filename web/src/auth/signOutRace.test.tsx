import { act, renderHook } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

// The race: offline with an expired token, supabase-js is retrying the refresh inside its auth lock. The person signs
// out. When the connection returns, the refresh succeeds and would write the session back, signing them in again.
const KEY = 'sb-localhost-auth-token'
const GUARD_KEY = 'mybooks.auth.signedOut'

const userA = { id: 'u1', aud: 'authenticated', email: 'a@example.com', app_metadata: {}, user_metadata: {}, created_at: '2025-01-01T00:00:00Z' }
const userB = { ...userA, id: 'u2', email: 'b@example.com' }

function sessionOf(user: typeof userA, expiresIn: number, refresh: string) {
  const now = Math.floor(Date.now() / 1000)
  return { access_token: `access.${refresh}`, refresh_token: refresh, token_type: 'bearer', expires_in: expiresIn, expires_at: now + expiresIn, user }
}
const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } })

async function load() {
  vi.resetModules()
  vi.stubEnv('VITE_SUPABASE_URL', 'http://localhost:54321')
  vi.stubEnv('VITE_SUPABASE_ANON_KEY', 'anon')
  const { useSession } = await import('./useSession')
  const { supabase } = await import('../supabase')
  const { signOut } = await import('./signOut')
  return { useSession, supabase, signOut }
}

const realFetch = globalThis.fetch
let online = false
let logoutWorks = true
let refreshedFor: typeof userA = userA
let route: (url: string) => Promise<Response> | Response = () => {
  throw new TypeError('Load failed')
}

beforeEach(() => {
  window.localStorage.clear()
  window.localStorage.setItem(KEY, JSON.stringify(sessionOf(userA, -7200, 'refresh-1')))
  online = false
  logoutWorks = true
  refreshedFor = userA
  vi.useFakeTimers()
  vi.spyOn(console, 'error').mockImplementation(() => {})
  route = (u) => {
    if (!online) throw new TypeError('Load failed')
    if (u.includes('/token')) return json(sessionOf(refreshedFor, 3600, 'refresh-2'))
    if (u.includes('/logout')) {
      if (!logoutWorks) throw new TypeError('Load failed')
      return new Response(null, { status: 204 })
    }
    throw new TypeError('Load failed')
  }
  globalThis.fetch = (async (url: unknown) => route(String(url))) as typeof fetch
})
afterEach(() => {
  vi.useRealTimers()
  vi.restoreAllMocks()
  vi.unstubAllEnvs()
  globalThis.fetch = realFetch
})

const advance = (ms: number) =>
  act(async () => {
    for (let i = 0; i < ms / 1000; i++) await vi.advanceTimersByTimeAsync(1_000)
  })

/** Runs [p] to its end, moving the fake clock on meanwhile (a slow sign-out waits a few seconds). */
async function through<T>(p: Promise<T>): Promise<T> {
  let done = false
  void p.then(() => (done = true), () => (done = true))
  for (let i = 0; i < 120 && !done; i++) await advance(1_000)
  return p
}

describe('signing out while a refresh is still retrying', () => {
  for (const works of [false, true]) {
    it(`anOfflineSignOutIsNotUndoneWhenTheConnectionReturns_logoutCall${works ? 'Works' : 'Fails'}`, async () => {
      logoutWorks = works
      const { useSession, supabase, signOut } = await load()
      const { result } = renderHook(() => useSession())
      await advance(6_000)
      expect(result.current.session?.user.id).toBe('u1')

      const out = signOut()
      await advance(6_000)
      await out
      expect(result.current.session).toBeNull()
      expect(window.localStorage.getItem(KEY)).toBeNull()

      // The connection returns: the refresh that was retrying succeeds, and the follow-up logout call goes out.
      online = true
      await advance(90_000)
      expect(window.localStorage.getItem(KEY)).toBeNull()
      expect(result.current.session).toBeNull()
      expect((await supabase.auth.getSession()).data.session).toBeNull()
    })
  }

  it('aTokenRefreshedEventForTheSignedOutAccountDoesNotReopenTheLibrary', async () => {
    const { useSession, supabase, signOut } = await load()
    const { result } = renderHook(() => useSession())
    await advance(6_000)
    await through(signOut())
    await advance(10_000)
    expect(window.localStorage.getItem(GUARD_KEY)).not.toBeNull()
    // Whatever arrives late for the account that signed out is ignored.
    await act(async () => {
      window.dispatchEvent(new StorageEvent('storage', { key: KEY }))
      await (supabase.auth as unknown as { _notifyAllSubscribers: (e: string, s: unknown) => Promise<void> })._notifyAllSubscribers(
        'TOKEN_REFRESHED',
        sessionOf(userA, 3600, 'late'),
      )
    })
    expect(result.current.session).toBeNull()
  })

  it('aStaleRefreshForAccountAFinishingAfterBSignsInDoesNotOverwriteB', async () => {
    const { signOut } = await load()
    const { authStorage } = await import('./guardedStorage')
    const { verifyCode } = await import('./verifyCode')
    await through(signOut()) // offline: the guard now names account A
    online = true
    refreshedFor = userB
    route = (u) => {
      if (u.includes('/verify')) return json(sessionOf(userB, 3600, 'b-refresh'))
      throw new TypeError('Load failed')
    }
    const { error } = await through(verifyCode('b@example.com', '123456'))
    expect(error).toBeNull()
    expect(JSON.parse(window.localStorage.getItem(KEY) ?? '{}').user.id).toBe('u2')

    // A's refresh, started before the sign-out, now finishes and tries to write its session.
    authStorage.setItem(KEY, JSON.stringify(sessionOf(userA, 3600, 'stale-a')))
    expect(JSON.parse(window.localStorage.getItem(KEY) ?? '{}').user.id).toBe('u2')
  })

  it('anExplicitSignInAsTheSameAccountWorksAgainAfterASignOut', async () => {
    const { signOut } = await load()
    const { verifyCode } = await import('./verifyCode')
    await through(signOut())
    online = true
    route = (u) => {
      if (u.includes('/verify')) return json(sessionOf(userA, 3600, 'a-new'))
      throw new TypeError('Load failed')
    }
    const { error } = await through(verifyCode('a@example.com', '123456'))
    expect(error).toBeNull()
    expect(JSON.parse(window.localStorage.getItem(KEY) ?? '{}').user.id).toBe('u1')
    expect(window.localStorage.getItem(GUARD_KEY)).toBeNull()
  })

  for (const [who, user] of [['TheSameAccount', userA], ['AnotherAccount', userB]] as const) {
    it(`aSignInAfterASignOutOpensTheLibrary_${who}`, async () => {
      const { useSession, signOut } = await load()
      const { verifyCode } = await import('./verifyCode')
      const { result } = renderHook(() => useSession())
      await advance(6_000)
      await through(signOut())
      expect(result.current.session).toBeNull()
      online = true
      route = (u) => {
        if (u.includes('/verify')) return json(sessionOf(user, 3600, 'new'))
        throw new TypeError('Load failed')
      }
      const { error } = await through(verifyCode(user.email, '123456'))
      expect(error).toBeNull()
      // The page itself must move on: the code is spent, so pressing Sign in again would say it didn't work.
      expect(result.current.session?.user.id).toBe(user.id)
    })
  }

  it('aNormalSignInRefreshAndSignOutAreUnaffected', async () => {
    window.localStorage.clear()
    online = true
    const { useSession, supabase, signOut } = await load()
    const { verifyCode } = await import('./verifyCode')
    route = (u) => {
      if (u.includes('/verify')) return json(sessionOf(userA, 3600, 'a-1'))
      if (u.includes('/token')) return json(sessionOf(userA, 3600, 'a-2'))
      if (u.includes('/logout')) return new Response(null, { status: 204 })
      throw new TypeError('x')
    }
    const { result } = renderHook(() => useSession())
    await advance(2_000)
    expect(result.current.session).toBeNull()
    await through(verifyCode('a@example.com', '123456'))
    expect(result.current.session?.user.id).toBe('u1')
    // A refresh with no guard up is stored as always.
    await through(supabase.auth.refreshSession())
    expect(JSON.parse(window.localStorage.getItem(KEY) ?? '{}').refresh_token).toBe('a-2')
    await through(signOut())
    expect(result.current.session).toBeNull()
    expect(window.localStorage.getItem(KEY)).toBeNull()
  })
})
