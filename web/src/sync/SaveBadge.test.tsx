import { act, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { SaveBadge } from './SaveBadge'
import type { SaveStatus } from './store'

/** Just what the badge reads from the store. */
class FakeStatus {
  status: SaveStatus = 'saved'
  localCopyFailed = false
  dismissRefused = vi.fn()
  private listeners = new Set<() => void>()
  subscribe = (cb: () => void) => {
    this.listeners.add(cb)
    return () => {
      this.listeners.delete(cb)
    }
  }
  set(s: SaveStatus) {
    this.status = s
    this.listeners.forEach((l) => l())
  }
}

describe('SaveBadge', () => {
  it('saysWhereTheChangesAre', () => {
    const store = new FakeStatus()
    render(<SaveBadge store={store} />)
    expect(screen.getByRole('status')).toHaveTextContent('Saved')

    act(() => store.set('saving'))
    expect(screen.getByRole('status')).toHaveTextContent('Saving…')

    act(() => store.set({ offline: 3 }))
    expect(screen.getByRole('status')).toHaveTextContent('Offline · 3 changes waiting')

    act(() => store.set({ offline: 1 }))
    expect(screen.getByRole('status')).toHaveTextContent('Offline · 1 change waiting')

    act(() => store.set({ rejected: 1 }))
    expect(screen.getByRole('status')).toHaveTextContent("1 change couldn't be saved")
  })

  it('anAuthFailureSaysSignInAgain', () => {
    const store = new FakeStatus()
    render(<SaveBadge store={store} />)
    act(() => store.set({ signedOut: true }))
    const text = screen.getByRole('status').textContent ?? ''
    expect(text).toContain('Signed out. Sign in again to save your changes.')
    expect(text).not.toContain('Offline')
  })

  it('refusedChangesStayVisibleUntilDismissed', async () => {
    const store = new FakeStatus()
    render(<SaveBadge store={store} />)
    act(() => store.set({ rejected: 2 }))
    const text = screen.getByRole('status').textContent ?? ''
    expect(text).toContain("2 changes couldn't be saved")
    // Never the server's own words.
    expect(text).not.toContain('varying')
    await userEvent.click(screen.getByRole('button', { name: 'Dismiss' }))
    expect(store.dismissRefused).toHaveBeenCalledTimes(1)
  })

  it('aBrokenLocalCopySaysSo', () => {
    const store = new FakeStatus()
    render(<SaveBadge store={store} />)
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
    act(() => {
      store.localCopyFailed = true
      store.set('saved')
    })
    expect(screen.getByRole('alert')).toHaveTextContent(
      "Couldn't open this browser's copy of your library. Your changes can't be saved here.",
    )
  })
})
