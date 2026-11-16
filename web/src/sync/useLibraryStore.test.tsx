import { act, renderHook, waitFor } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import type { LibraryStore } from './store'
import { useLibraryStore, useWarnBeforeClosing } from './useLibraryStore'

/** Just what the hook uses of a store. */
function fakeStore(id: string, pending = false) {
  return {
    id,
    load: vi.fn(() => Promise.resolve()),
    close: vi.fn(() => Promise.resolve()),
    hasPendingWrites: () => pending,
  }
}

describe('useLibraryStore', () => {
  it('oneStorePerSignedInUserClosedOnSignOutAndOnAccountChange', async () => {
    const made: ReturnType<typeof fakeStore>[] = []
    const create = (id: string) => {
      const s = fakeStore(id)
      made.push(s)
      return s as unknown as LibraryStore
    }
    const { result, rerender } = renderHook(({ id }) => useLibraryStore(id, create), {
      initialProps: { id: null as string | null },
    })
    expect(made).toHaveLength(0)
    expect(result.current.store).toBeNull()

    rerender({ id: 'u1' })
    await waitFor(() => expect(result.current.store).not.toBeNull())
    expect(made).toHaveLength(1)
    expect(made[0].load).toHaveBeenCalledTimes(1)
    // Rendering again for the same user keeps the store.
    rerender({ id: 'u1' })
    expect(made).toHaveLength(1)
    expect(made[0].close).not.toHaveBeenCalled()

    // Another account: the first store is closed and the second opens, never mixed.
    rerender({ id: 'u2' })
    expect(made[0].close).toHaveBeenCalledTimes(1)
    expect(result.current.store).toBeNull()
    await waitFor(() => expect(result.current.store).toBe(made[1]))
    expect(made).toHaveLength(2)

    // Signing out closes it.
    rerender({ id: null })
    expect(made[1].close).toHaveBeenCalledTimes(1)
    expect(result.current.store).toBeNull()
  })

  it('aStoreThatCannotOpenIsReportedAndClosed', async () => {
    const s = fakeStore('u1')
    s.load.mockRejectedValue(new Error('no database'))
    vi.spyOn(console, 'error').mockImplementation(() => {})
    const { result } = renderHook(() => useLibraryStore('u1', () => s as unknown as LibraryStore))
    await waitFor(() => expect(result.current.error).toBe('no database'))
    expect(result.current.store).toBeNull()
    expect(s.close).toHaveBeenCalled()
  })
})

describe('useWarnBeforeClosing', () => {
  const closing = () => {
    const event = new Event('beforeunload', { cancelable: true })
    act(() => {
      window.dispatchEvent(event)
    })
    return event
  }

  it('warnsOnlyWhileChangesAreNotSafe', () => {
    const store = fakeStore('u1', true)
    const { unmount } = renderHook(() => useWarnBeforeClosing(store as unknown as LibraryStore))
    expect(closing().defaultPrevented).toBe(true)

    const quiet = fakeStore('u1', false)
    unmount()
    renderHook(() => useWarnBeforeClosing(quiet as unknown as LibraryStore))
    expect(closing().defaultPrevented).toBe(false)
  })

  it('stopsWarningWhenTheStoreIsGone', () => {
    const store = fakeStore('u1', true)
    const { rerender } = renderHook(({ s }) => useWarnBeforeClosing(s), {
      initialProps: { s: store as unknown as LibraryStore | null },
    })
    rerender({ s: null })
    expect(closing().defaultPrevented).toBe(false)
  })
})
