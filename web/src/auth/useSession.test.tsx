import { act, renderHook } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

// These tests use the real supabase-js client (its storage is jsdom's localStorage), because what it does with an expired
// token and no connection is the whole question. src/supabase.ts builds it against http://localhost:54321 in tests,
// so its storage key is sb-localhost-auth-token.
const KEY = 'sb-localhost-auth-token'

function seedExpiredSession() {
  const now = Math.floor(Date.now() / 1000)
  window.localStorage.setItem(
    KEY,
    JSON.stringify({
      access_token: 'old.access.token',
      refresh_token: 'refresh-1',
      token_type: 'bearer',
      expires_in: 3600,
      expires_at: now - 2 * 3600,
      user: { id: 'u1', aud: 'authenticated', email: 'reader@example.com', app_metadata: {}, user_metadata: {}, created_at: '2025-01-01T00:00:00Z' },
    }),
  )
}

async function load() {
  vi.resetModules()
  // Whatever web/.env.local says, the client talks to this address (and so keeps its session under KEY).
  vi.stubEnv('VITE_SUPABASE_URL', 'http://localhost:54321')
  vi.stubEnv('VITE_SUPABASE_ANON_KEY', 'anon')
  const { useSession } = await import('./useSession')
  const { supabase } = await import('../supabase')
  return { useSession, supabase }
}

const realFetch = globalThis.fetch

beforeEach(() => {
  window.localStorage.clear()
  // The client times its retries with Date.now(), so the clock is faked too.
  vi.useFakeTimers()
  vi.spyOn(console, 'error').mockImplementation(() => {})
})
afterEach(() => {
  vi.useRealTimers()
  vi.restoreAllMocks()
  vi.unstubAllEnvs()
  globalThis.fetch = realFetch
})

/** Lets the client's refresh retries (200 ms, 400 ms, ... up to about 25 s, twice: once at start-up and once for getSession) run out. */
const settle = () =>
  act(async () => {
    // In steps: each retry is scheduled only after the one before has failed.
    for (let i = 0; i < 80; i++) await vi.advanceTimersByTimeAsync(1_000)
  })

describe('useSession', () => {
  it('supabaseJsReturnsNoSessionButKeepsItWhenTheRefreshFailsOnTheNetwork', async () => {
    seedExpiredSession()
    const { supabase } = await load()
    const pending = supabase.auth.getSession()
    await settle()
    const { data, error } = await pending
    expect(data.session).toBeNull()
    expect(error?.name).toBe('AuthRetryableFetchError')
    expect(window.localStorage.getItem(KEY)).not.toBeNull()
  })

  it('offlineWithAnExpiredTokenStaysSignedIn', async () => {
    seedExpiredSession()
    const { useSession } = await load()
    const { result } = renderHook(() => useSession())
    await settle()
    expect(result.current.loading).toBe(false)
    expect(result.current.session?.user.id).toBe('u1')
  })

  it('showsTheStoredAccountWithoutWaitingOutTheClientsRetriesWhenTheBrowserIsOffline', async () => {
    seedExpiredSession()
    vi.spyOn(navigator, 'onLine', 'get').mockReturnValue(false)
    const { useSession } = await load()
    const { result } = renderHook(() => useSession())
    await act(() => vi.advanceTimersByTimeAsync(10))
    expect(result.current.loading).toBe(false)
    expect(result.current.session?.user.id).toBe('u1')
  })

  it('aRejectedRefreshTokenSignsOut', async () => {
    seedExpiredSession()
    globalThis.fetch = () =>
      Promise.resolve(
        new Response(JSON.stringify({ error: 'invalid_grant', error_description: 'Invalid Refresh Token: Refresh Token Not Found' }), {
          status: 400,
          headers: { 'content-type': 'application/json' },
        }),
      )
    const { useSession } = await load()
    const { result } = renderHook(() => useSession())
    await settle()
    expect(result.current.loading).toBe(false)
    expect(result.current.session).toBeNull()
    expect(window.localStorage.getItem(KEY)).toBeNull()
  })

  it('supabaseJsSignOutDoesNothingOfflineWithAnExpiredToken', async () => {
    seedExpiredSession()
    const { supabase } = await load()
    const out = supabase.auth.signOut({ scope: 'local' })
    await settle()
    const { error } = await out
    expect(error?.name).toBe('AuthRetryableFetchError')
    expect(window.localStorage.getItem(KEY)).not.toBeNull()
  })

  it('aRealSignOutSignsOutEvenOffline', async () => {
    seedExpiredSession()
    vi.spyOn(navigator, 'onLine', 'get').mockReturnValue(false)
    const { useSession } = await load()
    const { signOut } = await import('./signOut')
    const { result } = renderHook(() => useSession())
    await act(() => vi.advanceTimersByTimeAsync(10))
    expect(result.current.session?.user.id).toBe('u1')
    await act(() => signOut())
    expect(result.current.session).toBeNull()
    expect(window.localStorage.getItem(KEY)).toBeNull()
  })

  it('aSlowSignOutIsDoneHereAfterAFewSeconds', async () => {
    seedExpiredSession()
    const { useSession } = await load()
    const { signOut } = await import('./signOut')
    const { result } = renderHook(() => useSession())
    await act(() => vi.advanceTimersByTimeAsync(5_000))
    expect(result.current.session?.user.id).toBe('u1')
    const out = signOut()
    await act(() => vi.advanceTimersByTimeAsync(5_000))
    await out
    expect(result.current.session).toBeNull()
    expect(window.localStorage.getItem(KEY)).toBeNull()
  })
})
