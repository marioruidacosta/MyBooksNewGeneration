import { act, render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { SaveBadge } from './SaveBadge'
import type { SaveStatus } from './store'

/** Just what the badge reads from the store. */
class FakeStatus {
  status: SaveStatus = 'saved'
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

    act(() => store.set({ rejected: 'value too long' }))
    expect(screen.getByRole('status')).toHaveTextContent("A change couldn't be saved: value too long")
  })
})
