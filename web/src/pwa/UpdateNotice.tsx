import { useState, useSyncExternalStore } from 'react'
import type { UpdateWatcher } from './updates'

type Pending = { hasPendingWrites?(): boolean; hasPending?(): boolean }

const never = () => () => {}

/**
 * "New version available. Reload", in the style of a toast, until it is used or put off. Reloading is held back while
 * a change is still being saved or sent, so nothing typed or unsent is lost: the person is told, and tries again.
 */
export function UpdateNotice({ watcher, store, publisher }: { watcher: UpdateWatcher | null; store?: Pending | null; publisher?: Pending | null }) {
  const available = useSyncExternalStore(watcher ? watcher.subscribe : never, () => watcher?.available ?? false)
  const [later, setLater] = useState(false)
  const [held, setHeld] = useState(false)
  if (!watcher || !available || later) return null

  const reload = async () => {
    // Tapping a button on iOS does not always take the focus out of a field: leave it, so what was typed is saved.
    if (document.activeElement instanceof HTMLElement) document.activeElement.blur()
    await new Promise((resolve) => setTimeout(resolve, 0))
    if (store?.hasPendingWrites?.() || publisher?.hasPending?.()) {
      setHeld(true)
      return
    }
    setHeld(false)
    watcher.apply()
  }

  return (
    // No live-region role of its own: it sits in the toast stack, which is one.
    <div className="update-notice">
      <div className="toast">
        <span>{held ? 'Still saving your changes. Try again in a moment.' : 'New version available.'}</span>
        <button type="button" onClick={() => void reload()}>
          Reload
        </button>
        <button type="button" className="toast__dismiss" aria-label="Later" onClick={() => setLater(true)}>
          <span aria-hidden="true">×</span>
        </button>
      </div>
    </div>
  )
}
