import { useSyncExternalStore } from 'react'
import { REFUSED_MESSAGE } from './limits'
import type { SaveStatus } from './store'

/** Just what the badge reads: the store's save status, whether its browser copy is broken, and its change feed. */
type StatusSource = {
  readonly status: SaveStatus
  readonly localCopyFailed?: boolean
  readonly notice?: string | null
  dismissRefused?: () => void
  subscribe: (cb: () => void) => () => void
}

function label(status: SaveStatus): string {
  if (status === 'saved') return 'Saved'
  if (status === 'saving') return 'Saving…'
  if ('offline' in status) {
    return `Offline · ${status.offline} ${status.offline === 1 ? 'change' : 'changes'} waiting`
  }
  if ('signedOut' in status) return 'Signed out. Sign in again to save your changes.'
  // The server's own text is for the log, not for the person: only how many.
  const n = status.rejected
  return `${n} ${n === 1 ? 'change' : 'changes'} couldn't be saved. ${REFUSED_MESSAGE}`
}

/**
 * "Saved", "Saving…", "Offline · N changes waiting", "Signed out", or how many changes were refused (until dismissed);
 * and, when this browser's copy of the library won't open, that nothing can be saved here.
 */
export function SaveBadge({ store }: { store: StatusSource }) {
  const status = useSyncExternalStore(store.subscribe, () => store.status)
  const brokenCopy = useSyncExternalStore(store.subscribe, () => store.localCopyFailed === true)
  const notice = useSyncExternalStore(store.subscribe, () => store.notice ?? null)
  const tone =
    status === 'saved' ? 'saved' : status === 'saving' ? 'saving' : 'offline' in status || 'signedOut' in status ? 'offline' : 'rejected'
  return (
    <>
      <span role="status" className={`save-badge save-badge--${tone}`}>
        {label(status)}
        {typeof status === 'object' && 'rejected' in status && store.dismissRefused && (
          <>
            {' '}
            <button type="button" onClick={() => store.dismissRefused?.()}>
              Dismiss
            </button>
          </>
        )}
      </span>
      {notice && (
        <span role="alert" className="save-badge save-badge--rejected">
          {notice}
        </span>
      )}
      {brokenCopy && (
        <span role="alert" className="save-badge save-badge--rejected">
          Couldn't open this browser's copy of your library. Your changes can't be saved here.
        </span>
      )}
    </>
  )
}
