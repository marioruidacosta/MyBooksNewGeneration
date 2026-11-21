import { supabase } from '../supabase'
import { duringSignIn } from './guardedStorage'

/**
 * The explicit sign-in: the emailed code. It first waits for whatever the auth client is still doing (a refresh retrying
 * after a sign-out), so that the guard's allowance covers only this sign-in's own write.
 */
export async function verifyCode(email: string, token: string) {
  try {
    await supabase.auth.getSession()
  } catch {
    // Only a wait: the sign-in itself reports any failure.
  }
  return duringSignIn(
    () => supabase.auth.verifyOtp({ email, token, type: 'email' }),
    (result) => result.data?.user?.id ?? null,
  )
}
