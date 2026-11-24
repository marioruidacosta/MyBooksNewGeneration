import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { applyTheme, loadTheme, THEME_KEY, THEMES, themeColor } from './appearance'
import { ThemeSwitch } from './ThemeSwitch'

beforeEach(() => {
  window.localStorage.clear()
  delete document.documentElement.dataset.theme
})

describe('appearance', () => {
  it('nightIsTheDefault', () => {
    expect(loadTheme()).toBe('night')
  })

  it('aChosenThemeIsRemembered', () => {
    window.localStorage.setItem(THEME_KEY, 'paper')
    expect(loadTheme()).toBe('paper')
    window.localStorage.setItem(THEME_KEY, 'something else')
    expect(loadTheme()).toBe('night')
  })

  it('theThemeIsSetOnThePage', () => {
    applyTheme('night')
    expect(document.documentElement.dataset.theme).toBe('night')
    applyTheme('paper')
    expect(document.documentElement.dataset.theme).toBe('paper')
  })

  it('theBrowsersThemeColourFollowsTheTheme', () => {
    const meta = document.createElement('meta')
    meta.name = 'theme-color'
    document.head.append(meta)
    applyTheme('night')
    expect(meta.content).toBe('#0f1c18')
    applyTheme('paper')
    expect(meta.content).toBe('#fbf7f0')
    meta.remove()
  })

  it('sixLooksCanBeChosen', () => {
    expect(THEMES.map((t) => t.label)).toEqual(['Night', 'Paper', 'Sepia', 'Midnight', 'Sage', 'Bordeaux'])
    window.localStorage.setItem(THEME_KEY, 'sage')
    expect(loadTheme()).toBe('sage')
  })

  it('midnightAndBordeauxAreNightInOtherColours', () => {
    // They keep every Night rule (data-theme) and bring their own colours (data-palette).
    applyTheme('midnight')
    expect(document.documentElement.dataset.theme).toBe('night')
    expect(document.documentElement.dataset.palette).toBe('midnight')
    applyTheme('sepia')
    expect(document.documentElement.dataset.theme).toBe('sepia')
    expect(document.documentElement.dataset.palette).toBeUndefined()
  })

  it('eachLookPaintsTheBrowsersBarsWithItsPaper', () => {
    expect(themeColor('sepia')).toBe('#f3e9d2')
    expect(themeColor('sage')).toBe('#eef1e6')
    expect(themeColor('midnight')).toBe('#0e1522')
    expect(themeColor('bordeaux')).toBe('#1c0f13')
  })

  it('theFirstPaintShowsTheChosenLook', () => {
    // index.html's script runs before the app: a Midnight page must not flash Night's green.
    const html = readFileSync(resolve(process.cwd(), 'index.html'), 'utf8')
    const script = /<script>([\s\S]*?)<\/script>/.exec(html)?.[1] ?? ''
    const meta = document.createElement('meta')
    meta.name = 'theme-color'
    document.head.append(meta)
    for (const [look, theme, palette, colour] of [
      ['midnight', 'night', 'midnight', '#0e1522'],
      ['bordeaux', 'night', 'bordeaux', '#1c0f13'],
      ['sepia', 'sepia', undefined, '#f3e9d2'],
      ['sage', 'sage', undefined, '#eef1e6'],
      ['night', 'night', undefined, '#0f1c18'],
      ['nonsense', 'night', undefined, '#0f1c18'],
    ] as const) {
      window.localStorage.setItem(THEME_KEY, look)
      delete document.documentElement.dataset.palette
      new Function(script)()
      expect(document.documentElement.dataset.theme, look).toBe(theme)
      expect(document.documentElement.dataset.palette, look).toBe(palette)
      expect(meta.content, look).toBe(colour)
    }
    meta.remove()
  })

  it('theSwitchChangesAndRemembersTheTheme', async () => {
    applyTheme(loadTheme())
    render(<ThemeSwitch />)
    const select = screen.getByLabelText('Theme')
    expect(select).toHaveValue('night')
    await userEvent.setup().selectOptions(select, 'Paper')
    expect(document.documentElement.dataset.theme).toBe('paper')
    expect(window.localStorage.getItem(THEME_KEY)).toBe('paper')
  })
})
