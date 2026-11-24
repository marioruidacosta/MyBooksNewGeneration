import type { GenerateSWOptions } from 'workbox-build'

/**
 * What the service worker keeps (vite.config.ts hands this to vite-plugin-pwa, which has Workbox write sw.js).
 *
 * Kept: the built app shell, so the site opens with no connection, the public book-news files, and the public covers
 * of the three book databases (they hold nothing private, and the National Library's server forbids the browser's own
 * cache, so each one was downloaded again every time).
 * Never kept: anything from Supabase (rows, sign-in, Edge Functions, signed cover addresses), or any other
 * cross-origin picture. The person's library and own cover photos live only in this browser's IndexedDB, per account,
 * never in a cache the service worker could serve to someone else.
 */
export const workboxOptions = {
  // The shell: html, js, css, the icons, and only the Latin and Latin-ext Literata files (Cyrillic, Greek and
  // Vietnamese are fetched by a page that needs them). No source maps, no json, nothing by default.
  globPatterns: [
    '**/*.{html,js,css,png}',
    '**/literata-latin-wght-*.woff2',
    '**/literata-latin-ext-wght-*.woff2',
  ],
  sourcemap: false,
  // A deep link such as /app/book/1 opens the shell offline. (The docs/ pages are outside this scope and untouched.)
  navigateFallback: 'index.html',
  cleanupOutdatedCaches: true,
  // A new version waits: the person decides when to reload (updates.ts), and it applies by itself on a cold start.
  skipWaiting: false,
  clientsClaim: true,
  runtimeCaching: [
    {
      // The public news files, published at /MyBooksNewGeneration/news/ by the 3-hourly build: the network first (they
      // change), the last copy when it does not answer within 4 seconds or there is no connection. Same origin only.
      urlPattern: ({ url, sameOrigin }: { url: URL; sameOrigin: boolean }) =>
        sameOrigin && /^\/MyBooksNewGeneration\/news\/[^/]+\.json$/.test(url.pathname),
      handler: 'NetworkFirst',
      options: {
        cacheName: 'mybooks-news',
        networkTimeoutSeconds: 4,
        expiration: { maxEntries: 6, maxAgeSeconds: 7 * 24 * 60 * 60 },
        cacheableResponse: { statuses: [200] },
      },
    },
    {
      // Written out here: Workbox copies this function into sw.js, so it can't use anything from this file.
      urlPattern: ({ url, request }: { url: URL; request: Request }) =>
        request.destination === 'image' &&
        url.protocol === 'https:' &&
        ['rnod.bnportugal.gov.pt', 'books.google.com', 'covers.openlibrary.org'].includes(url.hostname),
      // The saved picture at once, refreshed behind it: a bad copy (these responses can't be inspected) doesn't stay.
      handler: 'StaleWhileRevalidate',
      options: {
        cacheName: 'mybooks-public-covers',
        expiration: { maxEntries: 500, maxAgeSeconds: 30 * 24 * 60 * 60, purgeOnQuotaError: true },
        cacheableResponse: { statuses: [0, 200] },
      },
    },
  ],
} satisfies Partial<GenerateSWOptions>
