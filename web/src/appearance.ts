/** The site's two looks: Night (the reading room after dark, the default) and Paper (light, following the system). */
export type Theme = 'night' | 'paper'

export const THEMES: { id: Theme; label: string }[] = [
  { id: 'night', label: 'Night' },
  { id: 'paper', label: 'Paper' },
]

/** Where this browser keeps the chosen look. */
export const THEME_KEY = 'mybooks.theme'

/** The look chosen in this browser; Night until one is chosen. */
export function loadTheme(): Theme {
  try {
    const stored = window.localStorage.getItem(THEME_KEY)
    return stored === 'paper' || stored === 'night' ? stored : 'night'
  } catch {
    return 'night'
  }
}

/** Shows [theme] on the page (theme.css reads `data-theme` on the root element). */
export function applyTheme(theme: Theme): void {
  document.documentElement.dataset.theme = theme
}

/** Shows [theme] and keeps it for this browser's next visits. */
export function chooseTheme(theme: Theme): void {
  applyTheme(theme)
  try {
    window.localStorage.setItem(THEME_KEY, theme)
  } catch {
    // Not kept this time; the page still changes.
  }
}
