import { openDB, type DBSchema, type IDBPDatabase } from 'idb'
import { rowKey } from './api'
import { mergeFields } from './merge'
import type { Book, SyncRow, SyncTable } from './model'

/** A row waiting to be pushed. Its values are the row's own, read when it is sent. */
export type QueueEntry = { table: SyncTable; id: string }

/** A community post waiting to be sent (see `Publisher`): the book as it was before the changes and after them. */
export type PendingPublish =
  | { kind: 'publish'; before: Book | null; after: Book; created: boolean }
  | { kind: 'unpublish' }

/** A [PendingPublish] as kept in the cache, with the book it is about and the account that made it. */
export type StoredPublish = { id: string; owner: string; entry: PendingPublish }

/** A [StoredPublish] as stored: by `<writer>|<book id>`, with the tab (writer) that queued it. */
type DbPublish = StoredPublish & { writer?: string }

/** How often a tab says it is alive, and how long after its last sign a tab counts as gone. */
export const LEASE_HEARTBEAT_MS = 30_000
export const LEASE_MS = 120_000

/** What the person is told when version changes of the browser database get in the way (see [LocalDb.open]). */
export const BLOCKED_MESSAGE = 'Close your other MyBooks tabs to finish updating.'
export const SUPERSEDED_MESSAGE = 'MyBooks was updated in another tab. Reload this page.'

/** Called when the database version change involves other tabs. */
export type VersionHooks = {
  /** An older tab keeps the database open, so an upgrade waits. */
  onBlocked?: () => void
  /** A newer version wants in: this connection has been closed already. */
  onBlocking?: () => void
}

/** The database version: raise it only together with an `upgrade` step in [LocalDb.open]. */
const DB_VERSION = 2

interface LibraryDb extends DBSchema {
  /** The cached library: every pulled or edited row by its key (`rowKey`). */
  rows: { key: string; value: SyncRow }
  /** Rows waiting to be pushed, by `<writer>|<row key>`, so two tabs never remove each other's entries. */
  queue: { key: string; value: QueueEntry }
  /**
   * `seq`: the last pull cursor. `refused`: how many changes the server refused, until the person dismisses the
   * notice. `lease:<writer>`: when that tab last said it was alive.
   */
  state: { key: string; value: number }
  /** Community posts not yet sent, by `<writer>|<book id>` (added in version 2). */
  publishes: { key: string; value: DbPublish }
}

/** Equal values, a missing one counting as null. */
export function sameValue(a: unknown, b: unknown): boolean {
  if (a === b) return true
  if (a === undefined || b === undefined) return (a ?? null) === (b ?? null)
  if (typeof a !== 'object' || typeof b !== 'object' || a === null || b === null) return false
  return JSON.stringify(a) === JSON.stringify(b)
}

function sameRecord(a: Record<string, unknown>, b: Record<string, unknown>): boolean {
  const keys = new Set([...Object.keys(a), ...Object.keys(b)])
  for (const k of keys) if (!sameValue(a[k], b[k])) return false
  return true
}

/** Same values and times: nothing changed. */
export function sameRow(a: SyncRow, b: SyncRow): boolean {
  return sameRecord(a.fields, b.fields) && sameRecord(a.times, b.times)
}

/** One database per account, so two accounts on one browser never mix (nothing is cleared at sign-out). */
export function databaseName(owner: string): string {
  return `mybooks-library-${owner}`
}

export type Cached = {
  rows: Map<string, SyncRow>
  /** Row key → the stored queue keys for that row (one per tab that queued it). */
  queue: Map<string, { entry: QueueEntry; keys: string[] }>
  seq: number
  /** Changes the server refused that the person hasn't dismissed. */
  refused: number
}

/**
 * The library kept in the browser: rows, queue and pull cursor. Writes merge into what is stored per field, newest
 * wins (as a pull would), so a second tab of the same account can't put an older value over a newer one.
 */
export class LocalDb {
  private constructor(private readonly db: IDBPDatabase<LibraryDb>) {}

  /**
   * Every database version change goes through here, so it is handled once: a tab on an older version that holds the
   * database open makes this wait ([VersionHooks.onBlocked]), and this connection lets go when a newer version is
   * wanted ([VersionHooks.onBlocking]), so no tab can hang another.
   */
  static async open(owner: string, hooks: VersionHooks = {}): Promise<LocalDb> {
    let opened: LocalDb | null = null
    const db = await openDB<LibraryDb>(databaseName(owner), DB_VERSION, {
      upgrade(d, oldVersion) {
        if (oldVersion < 1) {
          d.createObjectStore('rows')
          d.createObjectStore('queue')
          d.createObjectStore('state')
        }
        if (oldVersion < 2) d.createObjectStore('publishes')
      },
      blocked() {
        hooks.onBlocked?.()
      },
      blocking() {
        opened?.close()
        hooks.onBlocking?.()
      },
    })
    opened = new LocalDb(db)
    return opened
  }

  async read(): Promise<Cached> {
    const tx = this.db.transaction(['rows', 'queue', 'state'], 'readonly')
    const rows = new Map<string, SyncRow>()
    let cursor = await tx.objectStore('rows').openCursor()
    while (cursor) {
      rows.set(cursor.key, cursor.value)
      cursor = await cursor.continue()
    }
    const queue: Cached['queue'] = new Map()
    let q = await tx.objectStore('queue').openCursor()
    while (q) {
      const row = q.key.slice(q.key.indexOf('|') + 1)
      const held = queue.get(row)
      if (held) held.keys.push(q.key)
      else queue.set(row, { entry: q.value, keys: [q.key] })
      q = await q.continue()
    }
    const seq = (await tx.objectStore('state').get('seq')) ?? 0
    const refused = (await tx.objectStore('state').get('refused')) ?? 0
    await tx.done
    return { rows, queue, seq, refused }
  }

  /**
   * Takes up the community posts of tabs that are gone (no writer, or a lease older than [LEASE_MS], or released): in
   * one transaction they become this tab's and are returned, so two tabs never take the same one. Another account's
   * are forgotten. A live tab's stay where they are.
   */
  async adoptPublishes(writer: string, owner: string, now: number): Promise<StoredPublish[]> {
    const tx = this.db.transaction(['publishes', 'state'], 'readwrite')
    const kept = tx.objectStore('publishes')
    const state = tx.objectStore('state')
    const adopted: StoredPublish[] = []
    const alive = new Map<string, boolean>()
    let cursor = await kept.openCursor()
    while (cursor) {
      const { writer: other, ...publish } = cursor.value
      const key = cursor.key
      if (other !== writer) {
        let live = other !== undefined && alive.get(other)
        if (other !== undefined && live === undefined) {
          const at = await state.get(`lease:${other}`)
          live = at !== undefined && now - at < LEASE_MS
          alive.set(other, live)
        }
        if (!live) {
          await kept.delete(key)
          if (publish.owner === owner) {
            await kept.put({ ...publish, writer }, `${writer}|${publish.id}`)
            adopted.push(publish)
          }
        }
      }
      cursor = await cursor.continue()
    }
    await tx.done
    return adopted
  }

  /**
   * One atomic write: rows to store (merged into the stored ones), row keys to remove for good, queue entries to add
   * and remove, and the pull cursor (never moved back).
   */
  async write(w: {
    put?: SyncRow[]
    drop?: string[]
    enqueue?: { key: string; entry: QueueEntry }[]
    dequeue?: string[]
    dequeueIfStored?: { table: SyncTable; id: string; keys: string[]; sent: SyncRow | null }[]
    seq?: number
    /** More refused changes: added to the stored count inside the transaction, so tabs add up. */
    refusedAdd?: number
    /** The person dismissed the notice: the count is 0. */
    refusedClear?: boolean
    /** The tab that keeps the community posts below. */
    writer?: string
    /** Community posts to keep for [writer], by book id: each replaces that book's entry, no other is touched. */
    publishPut?: StoredPublish[]
    /** Books whose community post [writer] no longer keeps (sent or dropped). */
    publishDelete?: string[]
    /** Says [writer] is alive at `at`, or (null) that it has closed. */
    lease?: { writer: string; at: number | null }
  }): Promise<void> {
    const tx = this.db.transaction(['rows', 'queue', 'state', 'publishes'], 'readwrite')
    const rows = tx.objectStore('rows')
    for (const row of w.put ?? []) {
      const key = rowKey(row.table, row.id)
      const stored = await rows.get(key)
      const { seq: _seq, ...plain } = stored ? mergeFields(stored, row) : row
      void _seq
      await rows.put(plain, key)
    }
    for (const key of w.drop ?? []) await rows.delete(key)
    const queue = tx.objectStore('queue')
    for (const { key, entry } of w.enqueue ?? []) await queue.put(entry, key)
    for (const key of w.dequeue ?? []) await queue.delete(key)
    // Another tab's entries: removed only when what was sent is the stored row (null: no row). Otherwise the cached
    // row holds that tab's newer change, and its entry stays for that tab, or for the next visit, to send.
    for (const { table, id, keys, sent } of w.dequeueIfStored ?? []) {
      const stored = (await rows.get(rowKey(table, id))) ?? null
      const same = stored === null || sent === null ? stored === sent : sameRow(stored, sent)
      if (same) for (const key of keys) await queue.delete(key)
    }
    if (w.seq !== undefined) {
      const state = tx.objectStore('state')
      const stored = (await state.get('seq')) ?? 0
      if (w.seq > stored) await state.put(w.seq, 'seq')
    }
    if (w.refusedAdd || w.refusedClear) {
      const state = tx.objectStore('state')
      const stored = w.refusedClear ? 0 : (await state.get('refused')) ?? 0
      await state.put(stored + (w.refusedAdd ?? 0), 'refused')
    }
    if (w.writer !== undefined) {
      const kept = tx.objectStore('publishes')
      for (const p of w.publishPut ?? []) await kept.put({ ...p, writer: w.writer }, `${w.writer}|${p.id}`)
      for (const id of w.publishDelete ?? []) await kept.delete(`${w.writer}|${id}`)
    }
    if (w.lease) {
      const state = tx.objectStore('state')
      const key = `lease:${w.lease.writer}`
      if (w.lease.at === null) await state.delete(key)
      else await state.put(w.lease.at, key)
    }
    await tx.done
  }

  close(): void {
    this.db.close()
  }
}
