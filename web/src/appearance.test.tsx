import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it } from 'vitest'
import { applyTheme, loadTheme, THEME_KEY } from './appearance'
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
