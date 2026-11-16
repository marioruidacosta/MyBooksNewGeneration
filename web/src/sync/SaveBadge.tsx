import { useSyncExternalStore } from 'react'
import type { SaveStatus } from './store'

/** Just what the badge reads: the store's save status and its change feed. */
type StatusSource = { readonly status: SaveStatus; subscribe: (cb: () => void) => () => void }

function label(status: SaveStatus): string {
  if (status === 'saved') return 'Saved'
  if (status === 'saving') return 'Saving…'
  if ('offline' in status) {
    return `Offline · ${status.offline} ${status.offline === 1 ? 'change' : 'changes'} waiting`
  }
  return `A change couldn't be saved: ${status.rejected}`
}

/** "Saved", "Saving…", "Offline · N changes waiting", or why a change was refused. */
export function SaveBadge({ store }: { store: StatusSource }) {
  const status = useSyncExternalStore(store.subscribe, () => store.status)
  const tone = status === 'saved' ? 'saved' : status === 'saving' ? 'saving' : 'offline' in status ? 'offline' : 'rejected'
  return (
    <span role="status" className={`save-badge save-badge--${tone}`}>
      {label(status)}
    </span>
  )
}
