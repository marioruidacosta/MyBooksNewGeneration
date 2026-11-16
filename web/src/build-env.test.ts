import { describe, expect, it } from 'vitest'
import { missingBuildEnv, requireBuildEnv } from './build-env'

describe('build environment guard', () => {
  it('aProductionBuildWithoutSupabaseSettingsFails', () => {
    expect(missingBuildEnv({})).toEqual(['VITE_SUPABASE_URL', 'VITE_SUPABASE_ANON_KEY'])
    expect(missingBuildEnv({ VITE_SUPABASE_URL: 'https://x.supabase.co', VITE_SUPABASE_ANON_KEY: '  ' })).toEqual([
      'VITE_SUPABASE_ANON_KEY',
    ])
    expect(() => requireBuildEnv('production', {})).toThrow(/VITE_SUPABASE_URL.*VITE_SUPABASE_ANON_KEY/)
    // Development and tests work without them; a complete production build passes.
    expect(() => requireBuildEnv('development', {})).not.toThrow()
    expect(() => requireBuildEnv('test', {})).not.toThrow()
    expect(() =>
      requireBuildEnv('production', { VITE_SUPABASE_URL: 'https://x.supabase.co', VITE_SUPABASE_ANON_KEY: 'k' }),
    ).not.toThrow()
  })
})
