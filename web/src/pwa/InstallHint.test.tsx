import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { HINT_KEY, InstallHint } from './InstallHint'

const SAFARI_IPHONE =
  'Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Mobile/15E148 Safari/604.1'
const CHROME_IPHONE =
  'Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) CriOS/126.0.0.0 Mobile/15E148 Safari/604.1'
const SAFARI_MAC =
  'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Safari/605.1.15'
const CHROME_ANDROID =
  'Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36'

/** Pretends to be [agent]; [standalone] is Safari's `navigator.standalone`, [displayModeStandalone] the display-mode media query. */
function pretend(agent: string, opts: { standalone?: boolean; displayModeStandalone?: boolean } = {}) {
  Object.defineProperty(navigator, 'userAgent', { value: agent, configurable: true })
  Object.defineProperty(navigator, 'standalone', { value: opts.standalone, configurable: true })
  window.matchMedia = ((q: string) => ({
    matches: q === '(display-mode: standalone)' && opts.displayModeStandalone === true,
    media: q,
    addEventListener: () => {},
    removeEventListener: () => {},
  })) as unknown as typeof window.matchMedia
}

const text = /Add MyBooks to your Home Screen: tap Share, then Add to Home Screen\./

beforeEach(() => window.localStorage.clear())
afterEach(() => {
  // @ts-expect-error jsdom has no matchMedia.
  delete window.matchMedia
  // @ts-expect-error not defined by jsdom.
  delete navigator.standalone
})

describe('the install hint', () => {
  it('installHintShowsOnlyInIosSafariAndOnce', async () => {
    const user = userEvent.setup()
    // Not on desktop, Android, another iPhone browser, or inside the installed app (either way of telling).
    for (const [agent, opts] of [
      [SAFARI_MAC, {}],
      [CHROME_ANDROID, {}],
      [CHROME_IPHONE, {}],
      [SAFARI_IPHONE, { standalone: true }],
      [SAFARI_IPHONE, { displayModeStandalone: true }],
    ] as const) {
      pretend(agent, opts)
      const { unmount } = render(<InstallHint />)
      expect(screen.queryByText(text), `${agent} ${JSON.stringify(opts)}`).not.toBeInTheDocument()
      unmount()
    }

    // In iPhone Safari, not installed: shown, with what to expect from the installed app, and dismissible.
    pretend(SAFARI_IPHONE, { standalone: false })
    const first = render(<InstallHint />)
    expect(screen.getByText(text)).toBeInTheDocument()
    expect(screen.getByText(/sign in once more with an email code/)).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Dismiss' }))
    expect(screen.queryByText(text)).not.toBeInTheDocument()
    expect(window.localStorage.getItem(HINT_KEY)).not.toBeNull()

    // Once: not shown again, even on the next visit.
    first.unmount()
    render(<InstallHint />)
    expect(screen.queryByText(text)).not.toBeInTheDocument()
  })

  it('anIpadThatPassesForAMacCountsAsIos', () => {
    pretend(SAFARI_MAC, {})
    Object.defineProperty(navigator, 'maxTouchPoints', { value: 5, configurable: true })
    render(<InstallHint />)
    expect(screen.getByText(text)).toBeInTheDocument()
    Object.defineProperty(navigator, 'maxTouchPoints', { value: 0, configurable: true })
  })
})
