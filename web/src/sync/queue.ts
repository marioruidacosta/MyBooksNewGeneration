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

interface LibraryDb extends DBSchema {
  /** The cached library: every pulled or edited row by its key (`rowKey`). */
  rows: { key: string; value: SyncRow }
  /** Rows waiting to be pushed, by `<writer>|<row key>`, so two tabs never remove each other's entries. */
  queue: { key: string; value: QueueEntry }
  /** `seq`: the last pull cursor. `refused`: how many changes the server refused, until the person dismisses the notice. */
  state: { key: string; value: number }
  /** Community posts not yet sent, by book id (added in version 2). */
  publishes: { key: string; value: StoredPublish }
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

  static async open(owner: string): Promise<LocalDb> {
    const db = await openDB<LibraryDb>(databaseName(owner), 2, {
      upgrade(d, oldVersion) {
        if (oldVersion < 1) {
          d.createObjectStore('rows')
          d.createObjectStore('queue')
          d.createObjectStore('state')
        }
        if (oldVersion < 2) d.createObjectStore('publishes')
      },
    })
    return new LocalDb(db)
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

  /** The community posts kept for the next visit. */
  async readPublishes(): Promise<StoredPublish[]> {
    return this.db.getAll('publishes')
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
    /** The number of refused changes shown to the person (0 clears it). */
    refused?: number
    /** Replaces every kept community post with these. */
    publishes?: StoredPublish[]
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
    if (w.refused !== undefined) await tx.objectStore('state').put(w.refused, 'refused')
    if (w.publishes) {
      const kept = tx.objectStore('publishes')
      await kept.clear()
      for (const p of w.publishes) await kept.put(p, p.id)
    }
    await tx.done
  }

  close(): void {
    this.db.close()
  }
}
