import { openDB, type DBSchema, type IDBPDatabase } from 'idb'
import { rowKey } from './api'
import { mergeFields } from './merge'
import type { SyncRow, SyncTable } from './model'

/** A row waiting to be pushed. Its values are the row's own, read when it is sent. */
export type QueueEntry = { table: SyncTable; id: string }

interface LibraryDb extends DBSchema {
  /** The cached library: every pulled or edited row by its key (`rowKey`). */
  rows: { key: string; value: SyncRow }
  /** Rows waiting to be pushed, by `<writer>|<row key>`, so two tabs never remove each other's entries. */
  queue: { key: string; value: QueueEntry }
  /** `seq`: the last pull cursor. */
  state: { key: string; value: number }
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
}

/**
 * The library kept in the browser: rows, queue and pull cursor. Writes merge into what is stored per field, newest
 * wins (as a pull would), so a second tab of the same account can't put an older value over a newer one.
 */
export class LocalDb {
  private constructor(private readonly db: IDBPDatabase<LibraryDb>) {}

  static async open(owner: string): Promise<LocalDb> {
    const db = await openDB<LibraryDb>(databaseName(owner), 1, {
      upgrade(d) {
        d.createObjectStore('rows')
        d.createObjectStore('queue')
        d.createObjectStore('state')
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
    await tx.done
    return { rows, queue, seq }
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
    seq?: number
  }): Promise<void> {
    const tx = this.db.transaction(['rows', 'queue', 'state'], 'readwrite')
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
    if (w.seq !== undefined) {
      const state = tx.objectStore('state')
      const stored = (await state.get('seq')) ?? 0
      if (w.seq > stored) await state.put(w.seq, 'seq')
    }
    await tx.done
  }

  close(): void {
    this.db.close()
  }
}
