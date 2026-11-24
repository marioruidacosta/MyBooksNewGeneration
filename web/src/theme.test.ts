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

  it('onAPhoneTheLibraryToolbarIsTwoEqualColumns', () => {
    // Search on its own row; Status | Tags; Lent or not | Recently added; Table/Covers | Add a book.
    const block = css.slice(css.lastIndexOf('@media (max-width: 52rem) {'))
    const rule = (selector: string) => block.slice(block.indexOf(`${selector} {`), block.indexOf('}', block.indexOf(`${selector} {`)))
    expect(rule('.toolbar')).toMatch(/grid-template-columns: repeat\(2, minmax\(0, 1fr\)\)/)
    expect(rule('.toolbar > *')).toMatch(/width: 100%/)
    expect(rule('.toolbar > *')).toMatch(/min-height: 2\.75rem/)
    expect(rule(".toolbar input[type='search']")).toMatch(/grid-column: 1 \/ -1/)
    expect(rule('.toolbar .view-switch, .toolbar button.primary')).toMatch(/order: 2/)
    expect(rule('.toolbar .toolbar__clear')).toMatch(/order: 1; grid-column: 1 \/ -1/)
  })

  it('onAPhoneTheBookHeaderStacksAsInTheApp', () => {
    // The cover slider on top, then the title and author centred under it.
    const block = css.slice(css.lastIndexOf('@media (max-width: 52rem) {'))
    const rule = (s: string) => block.slice(block.indexOf(`${s} {`), block.indexOf('}', block.indexOf(`${s} {`)))
    expect(rule('.book-hero__inner')).toMatch(/grid-template-columns: minmax\(0, 1fr\)/)
    expect(rule('.book-hero__text')).toMatch(/text-align: center/)
    expect(rule('.book-hero__state')).toMatch(/justify-content: center/)
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

describe('the four extra looks', () => {
  const looks = {
    sepia: tokens(":root[data-theme='sepia'] {"),
    sage: tokens(":root[data-theme='sage'] {"),
    midnight: tokens(":root[data-theme='night'][data-palette='midnight'] {"),
    bordeaux: tokens(":root[data-theme='night'][data-palette='bordeaux'] {"),
  }

  it('everyLookIsReadable', () => {
    for (const [name, t] of Object.entries(looks)) {
      for (const bg of ['--paper', '--paper-low', '--paper-deep']) {
        expect(contrast(t['--ink'], t[bg]), `${name} ink on ${bg}`).toBeGreaterThanOrEqual(7)
        expect(contrast(t['--ink-soft'], t[bg]), `${name} soft ink on ${bg}`).toBeGreaterThanOrEqual(4.5)
        expect(contrast(t['--star-empty'], t[bg]), `${name} empty star on ${bg}`).toBeGreaterThanOrEqual(3)
      }
      expect(contrast(t['--rule-strong'], t['--paper']), `${name} field border`).toBeGreaterThanOrEqual(3)
      expect(contrast(t['--on-teal'], t['--teal']), `${name} button text`).toBeGreaterThanOrEqual(4.5)
      expect(contrast(t['--on-teal-soft'], t['--teal-soft']), `${name} chosen item`).toBeGreaterThanOrEqual(4.5)
      expect(contrast(t['--teal'], t['--paper']), `${name} link`).toBeGreaterThanOrEqual(4.5)
    }
  })

  it('sepiaAndSageStayLightWhateverTheSystemSays', () => {
    expect(css).toMatch(/:root\[data-theme='sepia'\] \{\s*color-scheme: light;/)
    expect(css).toMatch(/:root\[data-theme='sage'\] \{\s*color-scheme: light;/)
  })

  it('sepiaAndSageGetTheDarkStatusStripInTheInstalledApp', () => {
    // Always light, so the white status text always needs it (Paper only when the system is light).
    for (const look of ['sepia', 'sage']) {
      const strip = inMediaOf('@media (display-mode: standalone) {', `[data-theme='${look}'] body::before`)
      expect(strip, look).toMatch(/height:\s*env\(safe-area-inset-top\)/)
    }
  })
})

/** The declarations of the first rule naming [selector] inside the media block that starts with [media]. */
function inMediaOf(media: string, selector: string): string {
  const start = css.indexOf(media)
  expect(start, media).toBeGreaterThanOrEqual(0)
  const block = css.slice(start, css.indexOf('\n}\n', start))
  const at = block.indexOf(selector)
  expect(at, selector).toBeGreaterThanOrEqual(0)
  return block.slice(block.indexOf('{', at) + 1, block.indexOf('}', at))
}

describe('the book page side column', () => {
  const declarations = (selector: string) => new RegExp(`(?:^|\\n)${selector.replace(/\./g, '\\.')} \\{([^}]*)\\}`).exec(css)?.[1] ?? ''

  it('theCoverTopIsLevelWithTheTitleAndAtAGlanceWithReading', () => {
    expect(declarations('.book-hero__inner')).toMatch(/align-items: start/)
    const glanceTop = /margin-top: ([\d.]+rem)/.exec(declarations('.glance'))?.[1]
    expect(glanceTop).toBeDefined()
    expect(declarations('.book-main')).toMatch(new RegExp(`margin-top: ${glanceTop}`))
    // On a phone the side column sits above the form, so neither keeps the gap.
    expect(css).toMatch(/\n {2}\.book-main \{ margin-top: 0; \}/)
  })

  it('atAGlanceAndWhereItIsHeadingsLookLikeReading', () => {
    expect(declarations('.book-section legend, .book-section h2')).toMatch(/font-size: 1\.375rem/)
    for (const heading of ['.glance h2', '.where-it-is h3']) {
      expect(declarations(heading), heading).toMatch(/font-size: 1\.375rem/)
      // Reading is a legend, so it takes the 1.5 line height of the page.
      expect(declarations(heading), heading).toMatch(/line-height: 1\.5/)
      expect(css, heading).toMatch(new RegExp(`\\[data-theme='night'\\] ${heading.replace('.', '\\.')}[^{]*\\{ color: #f3dca6; \\}`))
    }
  })
})

describe('the bookcase drawing', () => {
  const rule = (selector: string) => new RegExp(`(?:^|\\n)${selector.replace(/\./g, '\\.')} \\{([^}]*)\\}`).exec(css.replace(/\r/g, ''))?.[1] ?? ''

  it('isDrawnAsInTheAndroidApp', () => {
    expect(rule('.bookcase')).toMatch(/--shelf: 4\.5rem/)
    expect(rule('.bookcase')).not.toMatch(/box-shadow/)
    // Long titles on the spines must not stretch the shelves out of the frame.
    expect(rule('.bookcase')).toMatch(/grid-template-rows: minmax\(0, 1fr\)/)
    expect(rule('.bookcase--card')).toMatch(/--shelf: 2rem/)
    expect(rule('.bookcase--edit')).toMatch(/--shelf: 2\.75rem/)
    expect(rule('.bookcase--mini')).toMatch(/--shelf: 0\.75rem/)
    expect(rule('.bookcase__shelf')).toMatch(/background: #1c120b/)
    expect(rule('.bookcase__shelf')).not.toMatch(/border-bottom/)
    expect(rule('.spine')).not.toMatch(/box-shadow/)
    expect(rule('.spine__title')).toMatch(/400 6px/)
  })
})
