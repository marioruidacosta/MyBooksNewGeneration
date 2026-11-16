import { createClient, type SupabaseClient } from '@supabase/supabase-js'

// A production build fails without these (see build-env.ts); the placeholders
// only keep the client constructible in development and tests.
const url = import.meta.env.VITE_SUPABASE_URL || 'http://localhost:54321'
const anonKey = import.meta.env.VITE_SUPABASE_ANON_KEY || 'missing-anon-key'

// In development, say so (signing in would otherwise fail with a puzzling network error). Tests use placeholders on purpose.
if (import.meta.env.DEV && import.meta.env.MODE !== 'test') {
  const missing = [
    !import.meta.env.VITE_SUPABASE_URL && 'VITE_SUPABASE_URL',
    !import.meta.env.VITE_SUPABASE_ANON_KEY && 'VITE_SUPABASE_ANON_KEY',
  ].filter(Boolean)
  if (missing.length > 0) {
    console.warn(
      `MyBooks: ${missing.join(' and ')} ${missing.length === 1 ? 'is' : 'are'} not set, so signing in and syncing will not work. ` +
        'Set them in web/.env.local, then restart the dev server.',
    )
  }
}

export const supabase: SupabaseClient = createClient(url, anonKey)
