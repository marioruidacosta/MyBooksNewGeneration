import { describe, expect, it } from 'vitest'
import { workboxOptions } from './workbox'

const ORIGIN = 'https://marioruidacosta.github.io'

/** What Workbox hands a route's matcher for a request to [href] made by the page at [ORIGIN]. */
function matches(href: string, destination = 'image'): boolean {
  const url = new URL(href)
  const sameOrigin = url.origin === ORIGIN
  return (workboxOptions.runtimeCaching as readonly { urlPattern: unknown }[]).some((r) => {
    const p: unknown = r.urlPattern
    if (typeof p === 'function') return Boolean((p as (o: unknown) => unknown)({ url, sameOrigin, request: { url: href, destination }, event: {} }))
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
    expect(workboxOptions.runtimeCaching as unknown[]).toHaveLength(2)
  })

  it('onlyThePublicNewsFilesAreKeptForOffline', () => {
    expect(matches(`${ORIGIN}/MyBooksNewGeneration/news/news.json`)).toBe(true)
    expect(matches(`${ORIGIN}/MyBooksNewGeneration/news/events.json`)).toBe(true)
    // Other cross-origin images (news pictures) are left to the network.
    expect(matches('https://static01.nyt.com/images/2026/11/20/books/x-440.jpg')).toBe(false)
    const [news] = workboxOptions.runtimeCaching
    expect(news.handler).toBe('NetworkFirst')
    expect(news.options?.networkTimeoutSeconds).toBe(4)
    expect(news.options?.expiration?.maxEntries).toBeLessThanOrEqual(10)
    expect(news.options?.expiration?.maxAgeSeconds).toBeLessThanOrEqual(14 * 24 * 3600)
  })

  it('publicBookCoversAreKeptOnTheDevice', () => {
    for (const href of [
      'https://rnod.bnportugal.gov.pt/ImagesBN/winlibimg.aspx?skey=&doc=1&img=2&save=true',
      'https://books.google.com/books/content?id=x&printsec=frontcover&img=1&zoom=1',
      'https://covers.openlibrary.org/b/id/123-L.jpg',
    ]) expect(matches(href), href).toBe(true)
    const rule = workboxOptions.runtimeCaching[1]
    // The saved picture shows at once and is refreshed behind it, so a bad copy doesn't stay.
    expect(rule.handler).toBe('StaleWhileRevalidate')
    expect(rule.options?.cacheName).toBe('mybooks-public-covers')
    expect(rule.options?.cacheableResponse?.statuses).toEqual([0, 200])
    expect(rule.options?.expiration?.maxEntries).toBeLessThanOrEqual(500)
    expect(rule.options?.expiration?.maxAgeSeconds).toBe(30 * 24 * 3600)
    expect(rule.options?.expiration?.purgeOnQuotaError).toBe(true)
  })

  it('onlyPicturesFromTheBookDatabasesAreKept', () => {
    // Not a picture: a lookup on the same host.
    expect(matches('https://covers.openlibrary.org/b/id/123.json', '')).toBe(false)
    expect(matches('https://books.google.com/books/feeds/volumes?q=x', '')).toBe(false)
    // Not https, a look-alike host, or another site.
    expect(matches('http://covers.openlibrary.org/b/id/123-L.jpg')).toBe(false)
    expect(matches('https://covers.openlibrary.org.example.com/b/id/123-L.jpg')).toBe(false)
    expect(matches('https://example.com/books.google.com/x.jpg')).toBe(false)
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
