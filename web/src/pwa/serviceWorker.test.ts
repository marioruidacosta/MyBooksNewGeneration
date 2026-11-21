import { describe, expect, it } from 'vitest'
import { workboxOptions } from './workbox'

const ORIGIN = 'https://marioruidacosta.github.io'

/** What Workbox hands a route's matcher for a request to [href] made by the page at [ORIGIN]. */
function matches(href: string): boolean {
  const url = new URL(href)
  const sameOrigin = url.origin === ORIGIN
  return (workboxOptions.runtimeCaching as readonly { urlPattern: unknown }[]).some((r) => {
    const p: unknown = r.urlPattern
    if (typeof p === 'function') return Boolean((p as (o: unknown) => unknown)({ url, sameOrigin, request: { url: href }, event: {} }))
    if (p instanceof RegExp) return p.test(href)
    return false
  })
}

describe('the service worker', () => {
  it('theServiceWorkerNeverCachesSupabase', () => {
    // Every kind of address the app uses on Supabase: data, auth, Edge Functions, storage (signed cover URLs), realtime.
    const supabase = [
      'https://abcd1234.supabase.co/rest/v1/rpc/sync_pull',
      'https://abcd1234.supabase.co/auth/v1/token?grant_type=refresh_token',
      'https://abcd1234.supabase.co/functions/v1/lookup-isbn',
      'https://abcd1234.supabase.co/storage/v1/object/sign/covers/u1/b1.jpg?token=eyJ',
      'https://abcd1234.supabase.co/storage/v1/object/public/shared-covers/x.jpg',
      'wss://abcd1234.supabase.co/realtime/v1/websocket',
      // Even a look-alike path on another host is not the public news file.
      'https://abcd1234.supabase.co/MyBooksNewGeneration/news/news.json',
    ]
    for (const href of supabase) expect(matches(href), href).toBe(false)
    // A same-origin POST/GET to anything but the news files is not cached either.
    expect(matches(`${ORIGIN}/MyBooksNewGeneration/app/anything.json`)).toBe(false)
    // Nothing from Supabase is precached: only built files, by pattern.
    const precache = [...workboxOptions.globPatterns].join(' ')
    expect(precache).not.toMatch(/supabase|json/i)
    // And no catch-all route is left behind by a default.
    expect(workboxOptions.runtimeCaching as unknown[]).toHaveLength(1)
  })

  it('onlyThePublicNewsFilesAreKeptForOffline', () => {
    expect(matches(`${ORIGIN}/MyBooksNewGeneration/news/news.json`)).toBe(true)
    expect(matches(`${ORIGIN}/MyBooksNewGeneration/news/events.json`)).toBe(true)
    // Other cross-origin images (cover pictures, news pictures) are left to the network.
    expect(matches('https://static01.nyt.com/images/2026/11/20/books/x-440.jpg')).toBe(false)
    expect(matches('https://covers.openlibrary.org/b/id/123-M.jpg')).toBe(false)
    const [news] = workboxOptions.runtimeCaching
    expect(news.handler).toBe('NetworkFirst')
    expect(news.options?.networkTimeoutSeconds).toBe(4)
    expect(news.options?.expiration?.maxEntries).toBeLessThanOrEqual(10)
    expect(news.options?.expiration?.maxAgeSeconds).toBeLessThanOrEqual(14 * 24 * 3600)
  })

  it('theAppShellIsPrecachedAndDeepLinksOpenOffline', () => {
    expect(workboxOptions.navigateFallback).toBe('index.html')
    expect(workboxOptions.sourcemap).toBe(false)
    const patterns = workboxOptions.globPatterns.join(' ')
    // Only the Latin and Latin-ext fonts: the other subsets are fetched only by a page that needs them.
    expect(patterns).toMatch(/literata-latin-wght/)
    expect(patterns).toMatch(/literata-latin-ext-wght/)
    expect(patterns).not.toMatch(/cyrillic|greek|vietnamese/)
    expect(patterns).not.toMatch(/\.map/)
    // A new version waits for the person's say-so (see updates.ts).
    expect(workboxOptions.skipWaiting).toBe(false)
  })
})
