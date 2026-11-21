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

  it('inputsAreSixteenPixelsOnTouchDevices', () => {
    // iOS zooms the page when a field under 16px gets the focus. Every field, whatever more specific rule sized it
    // (the status select, the tag input, dates, the jump box): so the size is important.
    const decl = inMedia('@media (pointer: coarse), (max-width: 52rem) {', 'input, select, textarea')
    expect(decl).toMatch(/font-size:\s*16px\s*!important/)
    // Pinch zoom stays available.
    expect(readFileSync(resolve(process.cwd(), 'index.html'), 'utf8')).not.toMatch(/user-scalable\s*=\s*no|maximum-scale/)
  })
})
