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

/**
 * The colour the browser paints its own bars with (the status bar of the installed app, the address bar): the page's
 * paper. Paper follows the system, so it is the dark paper too when the system is dark. index.html's first-paint script
 * repeats these three values.
 */
export function themeColor(theme: Theme): string {
  if (theme === 'night') return '#0f1c18'
  const dark = typeof window.matchMedia === 'function' && window.matchMedia('(prefers-color-scheme: dark)').matches
  return dark ? '#16130f' : '#fbf7f0'
}

/** Shows [theme] on the page (theme.css reads data-theme on the root element) and in the browser's own bars. */
export function applyTheme(theme: Theme): void {
  document.documentElement.dataset.theme = theme
  document.querySelector('meta[name="theme-color"]')?.setAttribute('content', themeColor(theme))
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
