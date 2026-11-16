import { afterEach, describe, expect, it, vi } from 'vitest'

afterEach(() => {
  vi.unstubAllEnvs()
  vi.restoreAllMocks()
  vi.resetModules()
})

async function load(env: { url: string; key: string }, mode = 'development') {
  vi.resetModules()
  vi.stubEnv('MODE', mode)
  vi.stubEnv('DEV', mode !== 'production')
  vi.stubEnv('VITE_SUPABASE_URL', env.url)
  vi.stubEnv('VITE_SUPABASE_ANON_KEY', env.key)
  const warn = vi.spyOn(console, 'warn').mockImplementation(() => {})
  await import('./supabase')
  return warn
}

describe('supabase client', () => {
  it('warnsInDevWhenTheSettingsAreMissing', async () => {
    const warn = await load({ url: '', key: '' })
    expect(warn).toHaveBeenCalledTimes(1)
    const text = String(warn.mock.calls[0][0])
    expect(text).toContain('VITE_SUPABASE_URL')
    expect(text).toContain('VITE_SUPABASE_ANON_KEY')
  })

  it('isQuietWhenTheSettingsAreThere', async () => {
    const warn = await load({ url: 'https://x.supabase.co', key: 'anon' })
    // (supabase-js itself may warn about a second client, as the module is loaded again here.)
    expect(warn.mock.calls.map((c) => String(c[0])).filter((t) => t.includes('VITE_SUPABASE'))).toEqual([])
  })
})
