import 'fake-indexeddb/auto'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'

type Listener = (event: string, session: unknown) => void
const h = vi.hoisted(() => ({ listeners: [] as Array<(e: string, s: unknown) => void> }))

vi.mock('./supabase', () => ({
  supabase: {
    auth: {
      getSession: () => Promise.resolve({ data: { session: null } }),
      onAuthStateChange: (cb: Listener) => {
        h.listeners.push(cb)
        return { data: { subscription: { unsubscribe: () => {} } } }
      },
      signInWithOtp: () => Promise.resolve({ data: {}, error: null }),
      verifyOtp: () => {
        h.listeners.forEach((cb) => cb('SIGNED_IN', { user: { id: 'u1' } }))
        return Promise.resolve({ data: {}, error: null })
      },
    },
  },
}))

import { App } from './App'
import { rowKey, type SyncApi } from './sync/api'

const api: SyncApi = {
  pull: async () => ({ rows: [], last: 0 }),
  push: async (changes) => ({ applied: changes.map((c) => rowKey(c.table, c.id)), rejected: [] }),
  onPing: () => () => {},
}

describe('App', () => {
  it('signingInOpensTheLibrary', async () => {
    window.history.pushState({}, '', '/MyBooksNewGeneration/app/sign-in')
    const user = userEvent.setup()
    render(<App createApi={() => api} />)

    await user.type(await screen.findByLabelText('Email'), 'reader@example.com')
    await user.click(screen.getByRole('button', { name: 'Send code' }))
    await user.type(await screen.findByLabelText('Code'), '123456')
    await user.click(screen.getByRole('button', { name: 'Sign in' }))

    expect(await screen.findByRole('heading', { name: 'Library' })).toBeInTheDocument()
    expect(screen.queryByLabelText('Code')).not.toBeInTheDocument()
  })
})
