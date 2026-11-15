import { createClient, type SupabaseClient } from '@supabase/supabase-js'

// The build works without these (CI with empty variables); the placeholders
// only keep the client constructible, sign-in will not succeed with them.
const url = import.meta.env.VITE_SUPABASE_URL || 'http://localhost:54321'
const anonKey = import.meta.env.VITE_SUPABASE_ANON_KEY || 'missing-anon-key'

export const supabase: SupabaseClient = createClient(url, anonKey)
