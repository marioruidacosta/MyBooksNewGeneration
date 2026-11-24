import { useState } from 'react'
import { chooseTheme, loadTheme, THEMES, type Theme } from './appearance'

/** The look (Night, Paper, Sepia, Midnight, Sage or Bordeaux), from the top bar; the choice stays in this browser. */
export function ThemeSwitch() {
  const [theme, setTheme] = useState<Theme>(loadTheme)
  return (
    <select
      className="theme-switch"
      aria-label="Theme"
      value={theme}
      onChange={(e) => {
        const next = THEMES.find((t) => t.id === e.target.value)?.id ?? 'night'
        setTheme(next)
        chooseTheme(next)
      }}
    >
      {THEMES.map((t) => (
        <option key={t.id} value={t.id}>
          {t.label}
        </option>
      ))}
    </select>
  )
}
