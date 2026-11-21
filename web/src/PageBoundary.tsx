import { Component, useContext, type ErrorInfo, type ReactNode } from 'react'
import { SharingContext } from './share/SharingContext'
import { useOptionalStore } from './sync/StoreContext'

const RELOADED = 'pageBoundary.reloaded'

/** A failed dynamic import: the site was redeployed under an open tab, or the network dropped. */
const isChunkError = (e: unknown) =>
  e instanceof Error && /dynamically imported module|Loading chunk|Importing a module script failed|Failed to fetch/i.test(e.message)

function reloadedBefore(): boolean {
  try {
    return sessionStorage.getItem(RELOADED) === '1'
  } catch {
    // Without storage the flag can't stop a loop: don't reload by itself.
    return true
  }
}

type State = { failed: boolean }
type Props = { children: ReactNode; hasUnsavedChanges: () => boolean }

/** Around the lazy pages: when one fails to load, says so and offers Reload (once by itself for a chunk error). */
class Boundary extends Component<Props, State> {
  state: State = { failed: false }

  static getDerivedStateFromError(): State {
    return { failed: true }
  }

  componentDidCatch(error: unknown, info: ErrorInfo) {
    console.error(error, info.componentStack)
    // Never by itself over changes still being saved or sent: the Reload button below leaves that to the person.
    if (isChunkError(error) && !reloadedBefore() && !this.props.hasUnsavedChanges()) {
      try {
        sessionStorage.setItem(RELOADED, '1')
      } catch {
        return
      }
      location.reload()
    }
  }

  render() {
    if (!this.state.failed) return this.props.children
    return (
      <main>
        <p className="error" role="alert">
          This page couldn't load. The site may have been updated.
        </p>
        <button type="button" onClick={() => location.reload()}>
          Reload
        </button>
      </main>
    )
  }
}

/** Around the lazy pages: when one fails to load, says so and offers Reload (once by itself for a chunk error, unless changes are unsaved). */
export function PageBoundary({ children }: { children: ReactNode }) {
  const store = useOptionalStore()
  const publisher = useContext(SharingContext)
  return <Boundary hasUnsavedChanges={() => Boolean(store?.hasPendingWrites() || publisher?.hasPending())}>{children}</Boundary>
}
