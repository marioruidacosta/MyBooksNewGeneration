import { useEffect, useState, type FormEvent } from 'react'
import { QuoteOfTheDay } from '../quotes/QuoteOfTheDay'
import { supabase } from '../supabase'
import { verifyCode } from './verifyCode'

const CODE_ERROR = "That code didn't work. Check it, or send a new one."
const SEND_ERROR = "We couldn't send a code to that address. Check it and try again."

/** Where the email and the step are kept while the code is awaited (never the code itself). */
export const SIGNIN_KEY = 'mybooks.signin.pending'
const KEEP_MS = 10 * 60_000

/** The email a code was sent to a short while ago; iOS may have discarded the page while Mail was in front. */
function pendingEmail(): string | null {
  try {
    const raw = window.localStorage.getItem(SIGNIN_KEY)
    if (!raw) return null
    const value = JSON.parse(raw) as { email?: unknown; at?: unknown }
    if (typeof value.email === 'string' && typeof value.at === 'number' && Date.now() - value.at < KEEP_MS) return value.email
    window.localStorage.removeItem(SIGNIN_KEY)
  } catch {
    // Unreadable or no storage: start from the email step.
  }
  return null
}

function keepPending(email: string) {
  try {
    window.localStorage.setItem(SIGNIN_KEY, JSON.stringify({ email, at: Date.now() }))
  } catch {
    // Not kept: the person starts again from the email step.
  }
}

function forgetPending() {
  try {
    window.localStorage.removeItem(SIGNIN_KEY)
  } catch {
    // Nothing kept.
  }
}

export function SignIn() {
  const [pending] = useState(pendingEmail)
  const [email, setEmail] = useState(pending ?? '')
  const [token, setToken] = useState('')
  const [step, setStep] = useState<'email' | 'code'>(pending === null ? 'email' : 'code')
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  useEffect(() => {
    if (step !== 'code') return
    const timer = setTimeout(() => {
      forgetPending()
      setStep('email')
      setToken('')
    }, KEEP_MS)
    return () => clearTimeout(timer)
  }, [step])

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
      else {
        keepPending(email)
        setStep('code')
      }
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
      const { error: err } = await verifyCode(email, token)
      if (err) setError(CODE_ERROR)
      else forgetPending()
    } catch {
      setError(CODE_ERROR)
    } finally {
      setBusy(false)
    }
  }

  return (
    <main className="sign-in">
      <QuoteOfTheDay place="sign-in" />
      <div className="spines" aria-hidden="true" />
      <h1>MyBooks</h1>
      <p className="sign-in__lede">Your library, wherever you read. Sign in with the email you use in the app.</p>
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
          <button type="button" onClick={() => { forgetPending(); setStep('email'); setToken(''); setError(null) }}>
            Use another email
          </button>
        </form>
      )}
      {error && <p role="alert" className="error">{error}</p>}
    </main>
  )
}
