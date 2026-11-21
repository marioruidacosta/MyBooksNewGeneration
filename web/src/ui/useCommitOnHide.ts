import { useEffect } from 'react'

/**
 * Fields save when they lose focus, and iOS does not always send that when the app is sent to the background or the page
 * is discarded. When the page is hidden, the field being edited is left, so the same path that saves on blur runs.
 */
export function useCommitOnHide(): void {
  useEffect(() => {
    const commit = () => {
      const active = document.activeElement
      if (active instanceof HTMLInputElement || active instanceof HTMLTextAreaElement || active instanceof HTMLSelectElement) active.blur()
    }
    const onVisibility = () => {
      if (document.visibilityState === 'hidden') commit()
    }
    document.addEventListener('visibilitychange', onVisibility)
    window.addEventListener('pagehide', commit)
    return () => {
      document.removeEventListener('visibilitychange', onVisibility)
      window.removeEventListener('pagehide', commit)
    }
  }, [])
}
