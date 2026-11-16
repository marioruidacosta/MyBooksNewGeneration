import { Component, type ErrorInfo, type ReactNode } from 'react'

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

/** Around the lazy pages: when one fails to load, says so and offers Reload (once by itself for a chunk error). */
export class PageBoundary extends Component<{ children: ReactNode }, State> {
  state: State = { failed: false }

  static getDerivedStateFromError(): State {
    return { failed: true }
  }

  componentDidCatch(error: unknown, info: ErrorInfo) {
    console.error(error, info.componentStack)
    if (isChunkError(error) && !reloadedBefore()) {
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
