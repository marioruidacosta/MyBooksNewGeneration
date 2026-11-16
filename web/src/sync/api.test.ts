import type { SupabaseClient } from '@supabase/supabase-js'
import { describe, expect, it } from 'vitest'
import { createSupabaseSyncApi, SyncError } from './api'

type Rpc = { data: unknown; error: { message: string; code?: string } | null; status: number }

/** Just the parts of the client the adapter uses. */
function fakeClient(signedIn: string | null, answer: (name: string, args: Record<string, unknown>) => Rpc) {
  const calls: [string, Record<string, unknown>][] = []
  const channels: { topic: string; opts: unknown; handlers: (() => void)[]; onStatus?: (s: string) => void }[] = []
  const removed: string[] = []
  const client = {
    auth: { getSession: async () => ({ data: { session: signedIn ? { user: { id: signedIn } } : null } }) },
    rpc: async (name: string, args: Record<string, unknown>) => {
      calls.push([name, args])
      return answer(name, args)
    },
    channel(topic: string, opts: unknown) {
      const ch = {
        topic, opts, handlers: [] as (() => void)[], onStatus: undefined as ((s: string) => void) | undefined,
        on(_type: string, _filter: unknown, cb: () => void) {
          ch.handlers.push(cb)
          return ch
        },
        subscribe(cb: (s: string) => void) {
          ch.onStatus = cb
          return ch
        },
      }
      channels.push(ch)
      return ch
    },
    removeChannel: async (ch: { topic: string }) => {
      removed.push(ch.topic)
      return 'ok'
    },
  }
  return { client: client as unknown as SupabaseClient, calls, channels, removed }
}

describe('createSupabaseSyncApi', () => {
  it('pullsAndPushesInTheServersShapes', async () => {
    const { client, calls } = fakeClient('me', (name) =>
      name === 'sync_pull'
        ? {
          data: {
            rows: [{ table: 'books', id: 'b1', fields: { title: 'A' }, times: { title: 5 }, deleted: true, seq: 7 }],
            last: 7,
          },
          error: null, status: 200,
        }
        : {
          data: {
            applied: [{ table: 'books', id: 'b1' }],
            set_aside: [{ table: 'quotes', id: 'q1', reason: 'value too long' }],
          },
          error: null, status: 200,
        })
    const api = createSupabaseSyncApi(client, 'me')

    const page = await api.pull(3, 500)
    expect(calls[0]).toEqual(['sync_pull', { after: 3, max: 500 }])
    expect(page.last).toBe(7)
    expect(page.rows[0].fields).toEqual({ title: 'A', deleted: true })

    const result = await api.push([{ table: 'books', id: 'b1', fields: { title: 'A\u0000B' }, times: { title: 9 } }])
    expect(calls[1]).toEqual(['sync_push', {
      changes: [{ table: 'books', id: 'b1', fields: { title: 'AB' }, times: { title: 9 } }],
    }])
    expect(result).toEqual({ applied: ['books:b1'], rejected: [{ id: 'quotes:q1', reason: 'value too long' }] })
  })

  it('neverCallsTheServerForAnotherAccount', async () => {
    const { client, calls } = fakeClient('someone-else', () => ({ data: {}, error: null, status: 200 }))
    const api = createSupabaseSyncApi(client, 'me')
    await expect(api.push([])).rejects.toMatchObject({ kind: 'auth' })
    await expect(api.pull(0, 500)).rejects.toBeInstanceOf(SyncError)
    expect(calls).toEqual([])
  })

  it('tellsOfflineFromRefused', async () => {
    let next: Rpc = { data: null, error: { message: 'TypeError: Failed to fetch' }, status: 0 }
    const { client } = fakeClient('me', () => next)
    const api = createSupabaseSyncApi(client, 'me')
    await expect(api.pull(0, 500)).rejects.toMatchObject({ kind: 'offline' })
    next = { data: null, error: { message: 'invalid input syntax for type integer' }, status: 400 }
    await expect(api.push([])).rejects.toMatchObject({ kind: 'refused' })
    next = { data: null, error: { message: 'JWT expired', code: 'PGRST301' }, status: 401 }
    await expect(api.push([])).rejects.toMatchObject({ kind: 'auth' })
    next = { data: null, error: { message: 'boom' }, status: 503 }
    await expect(api.push([])).rejects.toMatchObject({ kind: 'other' })
  })

  it('listensOnTheOwnersPrivateChannel', () => {
    const { client, channels, removed } = fakeClient('me', () => ({ data: {}, error: null, status: 200 }))
    const api = createSupabaseSyncApi(client, 'me')
    let pings = 0
    const stop = api.onPing('me', () => pings++)

    expect(channels[0].topic).toBe('library:me')
    expect(channels[0].opts).toEqual({ config: { private: true } })
    channels[0].onStatus?.('SUBSCRIBED') // joined (or rejoined): pings may have been missed
    channels[0].handlers.forEach((h) => h())
    expect(pings).toBe(2)
    stop()
    expect(removed).toEqual(['library:me'])
  })
})
