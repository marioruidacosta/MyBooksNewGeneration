import { useState } from 'react'

/** Where this browser remembers that the hint was dismissed. */
export const HINT_KEY = 'mybooks.installHint.dismissed'

/** Safari on an iPhone or iPad (also an iPad that says it is a Mac), not another browser on iOS and not the installed app. */
function inIosSafariNotInstalled(): boolean {
  const agent = navigator.userAgent
  const ios = /iPhone|iPad|iPod/.test(agent) || (/Macintosh/.test(agent) && navigator.maxTouchPoints > 1)
  if (!ios || /CriOS|FxiOS|EdgiOS|OPiOS/.test(agent)) return false
  const installed =
    (navigator as Navigator & { standalone?: boolean }).standalone === true ||
    (typeof window.matchMedia === 'function' && window.matchMedia('(display-mode: standalone)').matches)
  return !installed
}

function dismissedBefore(): boolean {
  try {
    return window.localStorage.getItem(HINT_KEY) !== null
  } catch {
    return false
  }
}

/**
 * Once, in iPhone Safari, for someone signed in: how to put MyBooks on the Home Screen. It says the installed app signs
 * in on its own, because iOS gives it storage apart from Safari's.
 */
export function InstallHint() {
  const [shown, setShown] = useState(() => !dismissedBefore() && inIosSafariNotInstalled())
  if (!shown) return null
  const dismiss = () => {
    setShown(false)
    try {
      window.localStorage.setItem(HINT_KEY, '1')
    } catch {
      // Not kept: it may show again next visit.
    }
  }
  return (
    <aside className="install-hint" aria-label="Install MyBooks">
      <p>
        Add MyBooks to your Home Screen: tap Share, then Add to Home Screen. The app keeps its own sign-in, so you will sign in once more with an email code.
      </p>
      <button type="button" onClick={dismiss}>
        Dismiss
      </button>
    </aside>
  )
}
