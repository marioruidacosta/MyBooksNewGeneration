import type { GenerateSWOptions } from 'workbox-build'

/**
 * What the service worker keeps (vite.config.ts hands this to vite-plugin-pwa, which has Workbox write sw.js).
 *
 * Kept: the built app shell, so the site opens with no connection, and the public book-news files.
 * Never kept: anything from Supabase (rows, sign-in, Edge Functions, signed cover addresses), or any other
 * cross-origin picture. The person's library lives only in this browser's IndexedDB, per account, never in a cache the
 * service worker could serve to someone else.
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
  ],
} satisfies Partial<GenerateSWOptions>
