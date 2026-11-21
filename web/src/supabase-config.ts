/** The Supabase address; the placeholder only keeps the client constructible in development and tests. */
export const SUPABASE_URL: string = import.meta.env.VITE_SUPABASE_URL || 'http://localhost:54321'

/** Where supabase-js keeps the session in localStorage: its own rule, `sb-<first part of the host>-auth-token`. */
export function authStorageKey(url: string = SUPABASE_URL): string {
  return `sb-${new URL(url).hostname.split('.')[0]}-auth-token`
}
