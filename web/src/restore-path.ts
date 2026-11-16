/**
 * GitHub Pages has no server-side routing: a direct load of /app/book/1 is answered by the site's 404.html,
 * which sends the browser to /app/?p=book/1. This puts the real path back before the router starts.
 */
export function restoredUrl(base: string, search: string, hash: string): string | null {
  const p = new URLSearchParams(search).get('p')
  if (p === null) return null
  // Only paths inside the app: nothing with a scheme or a leading "//".
  if (/^[a-z][a-z0-9+.-]*:/i.test(p) || /^[/\\]{2}/.test(p)) return null
  return base + p.replace(/^\/+/, '') + hash
}

export function restorePath(): void {
  const url = restoredUrl(import.meta.env.BASE_URL, window.location.search, window.location.hash)
  if (url) window.history.replaceState(null, '', url)
}
