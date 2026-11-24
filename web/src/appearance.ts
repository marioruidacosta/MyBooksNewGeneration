/**
 * The site's looks: Night (the reading room after dark, the default), Paper (light, following the system), Sepia and
 * Sage (light whatever the system says), and Midnight and Bordeaux (Night's room in ink blue and in wine).
 */
export type Theme = 'night' | 'paper' | 'sepia' | 'midnight' | 'sage' | 'bordeaux'

export const THEMES: { id: Theme; label: string }[] = [
  { id: 'night', label: 'Night' },
  { id: 'paper', label: 'Paper' },
  { id: 'sepia', label: 'Sepia' },
  { id: 'midnight', label: 'Midnight' },
  { id: 'sage', label: 'Sage' },
  { id: 'bordeaux', label: 'Bordeaux' },
]

/** Where this browser keeps the chosen look. */
export const THEME_KEY = 'mybooks.theme'

/** Looks that are Night in other colours: theme.css keeps every Night rule and swaps the colours by data-palette. */
const NIGHT_PALETTES: Partial<Record<Theme, true>> = { midnight: true, bordeaux: true }

/** The paper of each look that doesn't follow the system. index.html's first-paint script repeats these. */
const PAPERS: Partial<Record<Theme, string>> = {
  night: '#0f1c18',
  sepia: '#f3e9d2',
  midnight: '#0e1522',
  sage: '#eef1e6',
  bordeaux: '#1c0f13',
}

/** The look chosen in this browser; Night until one is chosen. */
export function loadTheme(): Theme {
  try {
    const stored = window.localStorage.getItem(THEME_KEY)
    return THEMES.find((t) => t.id === stored)?.id ?? 'night'
  } catch {
    return 'night'
  }
}

/**
 * The colour the browser paints its own bars with (the status bar of the installed app, the address bar): the page's
 * paper. Paper follows the system, so it is the dark paper too when the system is dark.
 */
export function themeColor(theme: Theme): string {
  const paper = PAPERS[theme]
  if (paper) return paper
  const dark = typeof window.matchMedia === 'function' && window.matchMedia('(prefers-color-scheme: dark)').matches
  return dark ? '#16130f' : '#fbf7f0'
}

/** Shows [theme] on the page (theme.css reads data-theme and data-palette on the root element) and in the browser's bars. */
export function applyTheme(theme: Theme): void {
  const root = document.documentElement
  if (NIGHT_PALETTES[theme]) {
    root.dataset.theme = 'night'
    root.dataset.palette = theme
  } else {
    root.dataset.theme = theme
    delete root.dataset.palette
  }
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
