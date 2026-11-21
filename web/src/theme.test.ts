import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'

const css = readFileSync(resolve(process.cwd(), 'src/theme.css'), 'utf8')

const luminance = (hex: string) => {
  const [r, g, b] = [1, 3, 5].map((i) => {
    const c = parseInt(hex.slice(i, i + 2), 16) / 255
    return c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4
  })
  return 0.2126 * r + 0.7152 * g + 0.0722 * b
}
const contrast = (a: string, b: string) => {
  const [hi, lo] = [luminance(a), luminance(b)].sort((x, y) => y - x)
  return (hi + 0.05) / (lo + 0.05)
}

/** The custom properties of the block that starts at [opener]. */
function tokens(opener: string): Record<string, string> {
  const start = css.indexOf(opener)
  expect(start).toBeGreaterThanOrEqual(0)
  const block = css.slice(css.indexOf('{', start) + 1, css.indexOf('}', start))
  return Object.fromEntries([...block.matchAll(/(--[\w-]+):\s*(#[0-9a-f]{6})/gi)].map((m) => [m[1], m[2]]))
}

describe('theme', () => {
  it('emptyStarsInTheTableAreAtLeast3To1InEveryTheme', () => {
    // Not dimmed by opacity: that took them to about 1.6:1.
    const rule = /button\.star-btn \{[^}]*\}/.exec(css)?.[0] ?? ''
    expect(rule).not.toMatch(/opacity/)
    const light = tokens(':root {')
    const dark = tokens('@media (prefers-color-scheme: dark) {\n  :root')
    const night = tokens(":root[data-theme='night']")
    for (const t of [light, dark, night]) {
      for (const bg of ['--paper', '--paper-low', '--paper-deep']) {
        expect(contrast(t['--star-empty'], t[bg])).toBeGreaterThanOrEqual(3)
      }
    }
  })
})

describe('phone layout rules', () => {
  /** The declarations of the rule that starts with [selector] inside the media block that starts with [media]. */
  function inMedia(media: string, selector: string): string {
    const start = css.indexOf(media)
    expect(start, media).toBeGreaterThanOrEqual(0)
    // The block ends at the first line that is only a closing brace.
    const block = css.slice(start, css.indexOf('\n}', start))
    const rule = block.indexOf(selector)
    expect(rule, selector).toBeGreaterThanOrEqual(0)
    return block.slice(block.indexOf('{', rule) + 1, block.indexOf('}', rule))
  }

  it('theTagRemoveHitAreaIsAFullTapWideAndReachesOverItsOwnLabel', () => {
    const decl = inMedia('@media (pointer: coarse) {', '.tag-chip button::after')
    const [top, right, bottom, left] = /inset:\s*(-[\d.]+)rem\s+(-[\d.]+)rem\s+(-[\d.]+)rem\s+(-[\d.]+)rem/.exec(decl)!.slice(1).map((v) => -Number(v) * 16)
    expect(top + bottom + 21).toBeGreaterThanOrEqual(44) // the × is about 21 px tall and 19 px wide
    expect(left + right + 19).toBeGreaterThanOrEqual(44)
    expect(left).toBeGreaterThan(right) // towards its own label, away from the next chip
  })

  it('inputsAreSixteenPixelsOnTouchDevices', () => {
    // iOS zooms the page when a field under 16px gets the focus. Every field, whatever more specific rule sized it
    // (the status select, the tag input, dates, the jump box): so the size is important.
    const decl = inMedia('@media (pointer: coarse), (max-width: 52rem) {', 'input, select, textarea')
    expect(decl).toMatch(/font-size:\s*16px\s*!important/)
    // Pinch zoom stays available.
    expect(readFileSync(resolve(process.cwd(), 'index.html'), 'utf8')).not.toMatch(/user-scalable\s*=\s*no|maximum-scale/)
  })

  const PHONE = '@media (max-width: 52rem), (pointer: coarse) and (max-height: 30rem) {'
  const COARSE = '@media (pointer: coarse) {'

  it('theBottomBarIsOpaqueInBothThemes', () => {
    // Page text showed through the labels of a translucent bar.
    const base = inMedia(PHONE, '.bottom-bar {')
    expect(base).toMatch(/background:\s*var\(--paper\)/)
    expect(base).not.toMatch(/color-mix|backdrop-filter/)
    const night = inMedia(PHONE, "[data-theme='night'] .bottom-bar {")
    expect(night).toMatch(/background:\s*var\(--paper\)/)
    expect(night).not.toMatch(/color-mix/)
  })

  it('touchTargetsReach44Pixels', () => {
    expect(inMedia(COARSE, 'button, select {')).toMatch(/min-height:\s*2\.75rem/)
    expect(inMedia(COARSE, 'button.chip')).toMatch(/min-height:\s*2\.75rem/)
    const pick = inMedia(COARSE, 'button.date-pick')
    expect(pick).toMatch(/min-width:\s*2\.75rem/)
    expect(pick).toMatch(/min-height:\s*2\.75rem/)
    expect(inMedia(COARSE, 'button.shelf-meta')).toMatch(/min-width:\s*2\.75rem/)
  })

  it('onAPhoneTheBookCoverAlignsWithTheTopOfTheText', () => {
    const block = css.slice(css.lastIndexOf('@media (max-width: 52rem) {'))
    const rule = block.slice(block.indexOf('.book-hero__inner'), block.indexOf('}', block.indexOf('.book-hero__inner')))
    expect(rule).toMatch(/align-items: start/)
  })

  it('aLightPaperStatusBarGetsADarkStripInTheInstalledApp', () => {
    // black-translucent draws white status text; on light Paper that is white on cream. Night and dark Paper stay.
    const strip = inMedia('@media (display-mode: standalone) and (prefers-color-scheme: light) {', "[data-theme='paper'] body::before")
    expect(strip).toMatch(/height:\s*env\(safe-area-inset-top\)/)
    expect(strip).toMatch(/position:\s*fixed/)
    expect(strip).toMatch(/pointer-events:\s*none/)
  })

  it('theNewGlobalRulesDoNotChangeTheDesktop', () => {
    // The html background and the no-select on toasts are for phones only: they sit inside a media query.
    const outside = css.replace(/@media[^{]*\{[\s\S]*?\n\}\n/g, '')
    expect(outside).not.toMatch(/^html \{/m)
    expect(outside).not.toMatch(/\.toast[^{]*\{[^}]*user-select/)
    expect(css).not.toMatch(/\.shelf-progress--edit::after/)
  })
})
