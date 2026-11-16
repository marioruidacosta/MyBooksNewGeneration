import { createClient, type SupabaseClient } from '@supabase/supabase-js'

// A production build fails without these (see build-env.ts); the placeholders
// only keep the client constructible in development and tests.
const url = import.meta.env.VITE_SUPABASE_URL || 'http://localhost:54321'
const anonKey = import.meta.env.VITE_SUPABASE_ANON_KEY || 'missing-anon-key'

export const supabase: SupabaseClient = createClient(url, anonKey)
