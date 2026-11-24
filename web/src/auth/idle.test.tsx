import { act, render, renderHook, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { EXPIRED_KEY, LAST_USED_KEY } from './idle'

// The real supabase-js client, as in useSession.test.tsx: its storage is jsdom's localStorage.
const KEY = 'sb-localhost-auth-token'
const HOUR = 60 * 60 * 1000
const DAY = 24 * HOUR
const realFetch = globalThis.fetch

function seedSession() {
  const now = Math.floor(Date.now() / 1000)
  window.localStorage.setItem(KEY, JSON.stringify({
    access_token: 'access.token.here',
    refresh_token: 'refresh-1',
    token_type: 'bearer',
    expires_in: 3600,
    // Far ahead: nothing here depends on the client refreshing the token.
    expires_at: now + 60 * 24 * 3600,
    user: { id: 'u1', aud: 'authenticated', email: 'reader@example.com', app_metadata: {}, user_metadata: {}, created_at: '2025-01-01T00:00:00Z' },
  }))
}

const lastUsed = (ago: number) => window.localStorage.setItem(LAST_USED_KEY, String(Date.now() - ago))
const storedUse = () => Number(window.localStorage.getItem(LAST_USED_KEY))
const flush = () => act(async () => {
  for (let i = 0; i < 8; i++) await vi.advanceTimersByTimeAsync(1_000)
})

async function load() {
  vi.resetModules()
  vi.stubEnv('VITE_SUPABASE_URL', 'http://localhost:54321')
  vi.stubEnv('VITE_SUPABASE_ANON_KEY', 'anon')
  const { useSession } = await import('./useSession')
  const { signOut } = await import('./signOut')
  const { duringSignIn } = await import('./guardedStorage')
  return { useSession, signOut, duringSignIn }
}

const fetched = vi.fn(async () => new Response(null, { status: 204 }))

beforeEach(() => {
  window.localStorage.clear()
  vi.useFakeTimers()
  vi.spyOn(console, 'error').mockImplementation(() => {})
  fetched.mockClear()
  globalThis.fetch = fetched as unknown as typeof fetch
})
afterEach(() => {
  vi.useRealTimers()
  vi.restoreAllMocks()
  vi.unstubAllEnvs()
  globalThis.fetch = realFetch
})

describe('a session with no activity', () => {
  it('sevenDaysWithoutUseSignsOut', async () => {
    seedSession()
    lastUsed(7 * DAY + 1)
    const { useSession } = await load()
    const { result } = renderHook(() => useSession())
    await flush()
    expect(result.current.session).toBeNull()
    expect(window.localStorage.getItem(KEY)).toBeNull()
    expect(window.localStorage.getItem(EXPIRED_KEY)).toBe('1')
  })

  it('itEndsOnTheServerToo', async () => {
    seedSession()
    lastUsed(8 * DAY)
    const { useSession } = await load()
    renderHook(() => useSession())
    await flush()
    const urls = fetched.mock.calls.map((c) => String((c as unknown[])[0]))
    expect(urls.some((u) => u.includes('/auth/v1/logout') && u.includes('scope=local'))).toBe(true)
  })

  it('offlineItStillSignsOutHere', async () => {
    seedSession()
    lastUsed(8 * DAY)
    vi.spyOn(navigator, 'onLine', 'get').mockReturnValue(false)
    const { useSession } = await load()
    const { result } = renderHook(() => useSession())
    await flush()
    expect(result.current.session).toBeNull()
    expect(window.localStorage.getItem(KEY)).toBeNull()
  })

  it('usedWithinSevenDaysItStays', async () => {
    seedSession()
    // An hour short of the limit: the fake clock moves on while the session loads.
    lastUsed(7 * DAY - HOUR)
    const { useSession } = await load()
    const { result } = renderHook(() => useSession())
    await flush()
    expect(result.current.session?.user.id).toBe('u1')
    expect(window.localStorage.getItem(EXPIRED_KEY)).toBeNull()
  })

  it('theFirstLoadAfterTheUpdateStartsTheClock', async () => {
    seedSession()
    const { useSession } = await load()
    const { result } = renderHook(() => useSession())
    await flush()
    expect(result.current.session?.user.id).toBe('u1')
    expect(storedUse()).toBeGreaterThan(Date.now() - HOUR)
  })

  it('aClockMovedBackDoesNotSignOut', async () => {
    seedSession()
    lastUsed(-30 * DAY)
    const { useSession } = await load()
    const { result } = renderHook(() => useSession())
    await flush()
    expect(result.current.session?.user.id).toBe('u1')
  })

  it('aTabLeftOpenSignsOutWhenTheLimitPasses', async () => {
    seedSession()
    const { useSession } = await load()
    const { result } = renderHook(() => useSession())
    await flush()
    expect(result.current.session?.user.id).toBe('u1')
    lastUsed(8 * DAY)
    await act(async () => {
      await vi.advanceTimersByTimeAsync(HOUR + 1_000)
    })
    await flush()
    expect(result.current.session).toBeNull()
    expect(window.localStorage.getItem(EXPIRED_KEY)).toBe('1')
  })

  it('comingBackAfterTheLimitSignsOutInsteadOfCountingAsUse', async () => {
    seedSession()
    const { useSession } = await load()
    const { result } = renderHook(() => useSession())
    await flush()
    lastUsed(8 * DAY)
    act(() => {
      document.dispatchEvent(new Event('visibilitychange'))
    })
    await flush()
    expect(result.current.session).toBeNull()
  })

  it('usingTheAppRestartsTheClock', async () => {
    seedSession()
    const { useSession } = await load()
    const { result } = renderHook(() => useSession())
    await flush()
    lastUsed(6 * DAY)
    await act(async () => {
      await vi.advanceTimersByTimeAsync(2 * 60_000)
    })
    act(() => {
      window.dispatchEvent(new Event('pointerdown'))
    })
    expect(Date.now() - storedUse()).toBeLessThan(1_000)
    expect(result.current.session?.user.id).toBe('u1')
  })

  it('signingInRestartsTheClockAndClearsTheNotice', async () => {
    lastUsed(30 * DAY)
    window.localStorage.setItem(EXPIRED_KEY, '1')
    const { duringSignIn } = await load()
    await duringSignIn(async () => 'signed in', () => 'u1')
    expect(Date.now() - storedUse()).toBeLessThan(1_000)
    expect(window.localStorage.getItem(EXPIRED_KEY)).toBeNull()
  })

  it('aWrongCodeKeepsTheNotice', async () => {
    window.localStorage.setItem(EXPIRED_KEY, '1')
    const { duringSignIn } = await load()
    await duringSignIn(async () => 'failed', () => null)
    expect(window.localStorage.getItem(EXPIRED_KEY)).toBe('1')
  })

  it('signingOutEndsOnlyThisDevice', async () => {
    seedSession()
    const { signOut } = await load()
    await act(async () => {
      const out = signOut()
      await vi.advanceTimersByTimeAsync(100)
      await out
    })
    const logout = fetched.mock.calls.map((c) => String((c as unknown[])[0])).find((u) => u.includes('/auth/v1/logout'))
    expect(logout).toContain('scope=local')
  })

  it('theSignInPageSaysWhy', async () => {
    window.localStorage.setItem(EXPIRED_KEY, '1')
    await load()
    const { SignIn } = await import('./SignIn')
    render(<SignIn />)
    expect(screen.getByText(/signed out after 7 days without use/)).toBeInTheDocument()
  })

  it('anOrdinarySignInPageSaysNothing', async () => {
    await load()
    const { SignIn } = await import('./SignIn')
    render(<SignIn />)
    expect(screen.queryByText(/signed out after 7 days/)).not.toBeInTheDocument()
  })
})
