/** The settings the site cannot work without; a deployed build without them is a dead site (sign-in never succeeds). */
const REQUIRED = ['VITE_SUPABASE_URL', 'VITE_SUPABASE_ANON_KEY'] as const

/** The required settings that are missing or blank in [env]. */
export const missingBuildEnv = (env: Record<string, string | undefined>): string[] =>
  REQUIRED.filter((name) => (env[name] ?? '').trim() === '')

/** Throws for a production build without the settings; development and tests work without them. */
export function requireBuildEnv(mode: string, env: Record<string, string | undefined>): void {
  if (mode !== 'production') return
  const missing = missingBuildEnv(env)
  if (missing.length > 0) {
    throw new Error(
      `The production build needs ${missing.join(' and ')}. Set them (in CI: the repository variables of the same name) and build again.`,
    )
  }
}
