import { useState, type FormEvent } from 'react'
import { supabase } from '../supabase'

const CODE_ERROR = "That code didn't work. Check it, or send a new one."
const SEND_ERROR = "We couldn't send a code to that address. Check it and try again."

export function SignIn() {
  const [email, setEmail] = useState('')
  const [token, setToken] = useState('')
  const [step, setStep] = useState<'email' | 'code'>('email')
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  async function sendCode(e: FormEvent) {
    e.preventDefault()
    setBusy(true)
    setError(null)
    try {
      const { error: err } = await supabase.auth.signInWithOtp({
        email,
        options: { shouldCreateUser: false },
      })
      if (err) setError(SEND_ERROR)
      else setStep('code')
    } catch {
      setError(SEND_ERROR)
    } finally {
      setBusy(false)
    }
  }

  async function signIn(e: FormEvent) {
    e.preventDefault()
    setBusy(true)
    setError(null)
    try {
      const { error: err } = await supabase.auth.verifyOtp({ email, token, type: 'email' })
      if (err) setError(CODE_ERROR)
    } catch {
      setError(CODE_ERROR)
    } finally {
      setBusy(false)
    }
  }

  return (
    <main className="sign-in">
      <div className="spines" aria-hidden="true" />
      <h1>MyBooks</h1>
      <p className="sign-in__lede">Your library at your desk. Sign in with the email you use in the app.</p>
      {step === 'email' ? (
        <form onSubmit={sendCode}>
          <label>
            Email
            <input type="email" value={email} onChange={(e) => setEmail(e.target.value)} required autoFocus />
          </label>
          <button type="submit" disabled={busy}>Send code</button>
        </form>
      ) : (
        <form onSubmit={signIn}>
          <p>We sent a code to {email}.</p>
          <label>
            Code
            <input
              inputMode="numeric"
              autoComplete="one-time-code"
              value={token}
              onChange={(e) => setToken(e.target.value)}
              required
              autoFocus
            />
          </label>
          <button type="submit" disabled={busy}>Sign in</button>
          <button type="button" onClick={() => { setStep('email'); setToken(''); setError(null) }}>
            Use another email
          </button>
        </form>
      )}
      {error && <p role="alert" className="error">{error}</p>}
    </main>
  )
}
