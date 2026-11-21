import { isAuthRetryableFetchError, type SupabaseClient } from '@supabase/supabase-js'
import { wireToRow, type SyncRow, type SyncTable } from './model'

/**
 * One change as `sync_push` takes it: the row's current fields and their times (`deleted` travels inside `fields`, as
 * an ordinary field with its own time). The server keeps each field only when its time is newer than its own.
 */
export type Change = {
  table: SyncTable
  id: string
  fields: Record<string, unknown>
  times: Record<string, number>
}

export type PullPage = { rows: SyncRow[]; last: number }

/**
 * What the server did with a push. `applied` and `rejected[].id` hold row keys ([rowKey]: table and id), since the
 * server names a row by both.
 */
export type PushResult = { applied: string[]; rejected: { id: string; reason: string }[] }

/** The cloud library, as the store sees it; the tests use a fake. */
export interface SyncApi {
  pull(after: number, max: number): Promise<PullPage>
  push(changes: Change[]): Promise<PushResult>
  /** Calls [cb] on every ping from another device, and each time the channel is (re)joined (pings may be missed). */
  onPing(owner: string, cb: () => void): () => void
}

/** A row's key everywhere in the store: its table and id. */
export function rowKey(table: string, id: string): string {
  return `${table}:${id}`
}

/**
 * - `offline`: no answer (network down, timeout); retried later.
 * - `auth`: the session is missing, expired, or isn't the library's owner (401, 403); retried later (the app signs
 *   out).
 * - `refused`: the request itself couldn't be read (a 400; `sync_push` sets a bad row aside on its own, so a 400 for
 *   the whole request means its body): sending it again can't work, but each row alone may.
 * - `tooLarge`: the request was too big (413): sent again in smaller parts, never set aside.
 * - `other`: anything else (a 5xx, 404 while the schema reloads, 408, 429); retried later.
 */
export type SyncErrorKind = 'offline' | 'auth' | 'refused' | 'tooLarge' | 'other'

export class SyncError extends Error {
  readonly kind: SyncErrorKind

  constructor(kind: SyncErrorKind, message: string) {
    super(message)
    this.name = 'SyncError'
    this.kind = kind
  }
}

/** The pages `sync_pull` is asked for, as the app. */
export const PULL_MAX = 500

/** At most this many changes per `sync_push` (the server takes 200). */
export const PUSH_BATCH = 100

type RpcError = { message?: string; code?: string } | null

/** The [SyncError] a failed RPC answer stands for. */
export function syncErrorOf(error: RpcError, status: number): SyncError {
  const message = error?.message ?? 'error'
  if (status === 0) return new SyncError('offline', message)
  if (status === 401 || status === 403 || error?.code === 'PGRST301' || /JWT expired/i.test(message)) {
    return new SyncError('auth', message)
  }
  if (status === 400) return new SyncError('refused', message)
  if (status === 413) return new SyncError('tooLarge', message)
  // Not about the data (a 404 while the schema reloads, 408, 429, a 5xx): the rows wait for a retry.
  return new SyncError('other', message)
}

/** [value] without U+0000 in any string: Postgres's jsonb can't hold it, and the whole request would be refused. */
function withoutNul(value: unknown): unknown {
  if (typeof value === 'string') return value.includes('\u0000') ? value.split('\u0000').join('') : value
  if (Array.isArray(value)) return value.map(withoutNul)
  if (value !== null && typeof value === 'object') {
    return Object.fromEntries(Object.entries(value).map(([k, v]) => [k, withoutNul(v)]))
  }
  return value
}

type WireRow = Parameters<typeof wireToRow>[0]

/**
 * [SyncApi] over `sync_pull`, `sync_push` and the private Realtime channel `library:<owner>`. Each data call goes out
 * only while the signed-in account (whose token the call carries) is [owner]; otherwise it fails as `auth` without
 * calling the server, so one account's queue never reaches another account's library.
 */
export function createSupabaseSyncApi(client: SupabaseClient, owner: string): SyncApi {
  async function asOwner(): Promise<void> {
    const { data, error } = await client.auth.getSession()
    if (data.session?.user.id === owner) return
    // No session because the token could not be renewed over the network is a wait, not a sign-out: nothing is sent with the stale token.
    if (!data.session && isAuthRetryableFetchError(error)) throw new SyncError('offline', 'the session could not be renewed: no connection')
    throw new SyncError('auth', "not signed in as this library's account")
  }

  async function rpc(name: string, args: Record<string, unknown>): Promise<Record<string, unknown>> {
    await asOwner()
    let result: { data: unknown; error: RpcError; status: number }
    try {
      result = await client.rpc(name, args)
    } catch (e) {
      throw new SyncError('offline', e instanceof Error ? e.message : String(e))
    }
    if (result.error) throw syncErrorOf(result.error, result.status)
    return (result.data ?? {}) as Record<string, unknown>
  }

  return {
    async pull(after, max) {
      const data = await rpc('sync_pull', { after, max })
      const rows = Array.isArray(data.rows) ? (data.rows as WireRow[]).map(wireToRow) : []
      return { rows, last: typeof data.last === 'number' ? data.last : after }
    },

    async push(changes) {
      const data = await rpc('sync_push', {
        changes: changes.map((c) => ({ table: c.table, id: c.id, fields: withoutNul(c.fields), times: c.times })),
      })
      type Ref = { table: string; id: string; reason?: string }
      const applied = Array.isArray(data.applied) ? (data.applied as Ref[]) : []
      const setAside = Array.isArray(data.set_aside) ? (data.set_aside as Ref[]) : []
      return {
        applied: applied.map((r) => rowKey(r.table, r.id)),
        rejected: setAside.map((r) => ({ id: rowKey(r.table, r.id), reason: r.reason ?? '' })),
      }
    },

    onPing(channelOwner, cb) {
      const channel = client.channel(`library:${channelOwner}`, { config: { private: true } })
      channel.on('broadcast', { event: 'changed' }, () => cb())
      channel.subscribe((status) => {
        if (status === 'SUBSCRIBED') cb()
      })
      return () => {
        void client.removeChannel(channel)
      }
    },
  }
}
