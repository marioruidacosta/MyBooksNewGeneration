import type { SyncRow } from './model'

/**
 * Per field, the remote value wins only when its time is newer than the local one (a missing local time counts
 * as -1; equal keeps local). A remote field without a time is ignored. As the app's `mergeFields`.
 */
export function mergeFields(local: SyncRow | undefined, remote: SyncRow): SyncRow {
  if (!local) return remote
  const fields = { ...local.fields }
  const times = { ...local.times }
  for (const [f, v] of Object.entries(remote.fields)) {
    const rt = remote.times[f]
    if (rt === undefined) continue
    if (rt > (local.times[f] ?? -1)) {
      fields[f] = v
      times[f] = rt
    }
  }
  return { table: remote.table, id: remote.id, fields, times, seq: remote.seq }
}

/** The times of an edit: every changed field gets `now`. */
export function stamp(changed: string[], now: number): Record<string, number> {
  const out: Record<string, number> = {}
  for (const f of changed) out[f] = now
  return out
}
