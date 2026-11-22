import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'

const signInWithOtp = vi.fn()
const verifyOtp = vi.fn()

vi.mock('../supabase', () => ({
  supabase: { auth: { signInWithOtp: (a: unknown) => signInWithOtp(a), verifyOtp: (a: unknown) => verifyOtp(a) } },
}))

import { SignIn } from './SignIn'
const SIGNIN_KEY = 'mybooks.signin.pending'

describe('SignIn', () => {
  beforeEach(() => {
    window.localStorage.clear()
    signInWithOtp.mockReset().mockResolvedValue({ data: {}, error: null })
    verifyOtp.mockReset()
  })

  it('sendsTheCodeThenSignsIn', async () => {
    verifyOtp.mockResolvedValue({ data: {}, error: null })
    const user = userEvent.setup({ delay: null })
    render(<SignIn />)

    await user.type(screen.getByLabelText('Email'), 'reader@example.com')
    await user.click(screen.getByRole('button', { name: 'Send code' }))

    expect(signInWithOtp).toHaveBeenCalledWith({
      email: 'reader@example.com',
      options: { shouldCreateUser: true },
    })
    await user.type(await screen.findByLabelText('Code'), '123456')
    await user.click(screen.getByRole('button', { name: 'Sign in' }))

    await waitFor(() =>
      expect(verifyOtp).toHaveBeenCalledWith({ email: 'reader@example.com', token: '123456', type: 'email' }),
    )
  })

  it('showsTheServerError', async () => {
    verifyOtp.mockResolvedValue({ data: {}, error: { message: 'Token has expired or is invalid' } })
    const user = userEvent.setup({ delay: null })
    render(<SignIn />)

    await user.type(screen.getByLabelText('Email'), 'reader@example.com')
    await user.click(screen.getByRole('button', { name: 'Send code' }))
    await user.type(await screen.findByLabelText('Code'), '000000')
    await user.click(screen.getByRole('button', { name: 'Sign in' }))

    expect(await screen.findByText("That code didn't work. Check it, or send a new one.")).toBeInTheDocument()
  })

  it("aNetworkFailureWhileSendingCanBeRetried", async () => {
    signInWithOtp.mockRejectedValue(new TypeError("Failed to fetch"))
    const user = userEvent.setup({ delay: null })
    render(<SignIn />)

    await user.type(screen.getByLabelText("Email"), "reader@example.com")
    await user.click(screen.getByRole("button", { name: "Send code" }))

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "We couldn't send a code to that address. Check it and try again.",
    )
    expect(screen.getByRole("button", { name: "Send code" })).toBeEnabled()
  })

  it("aNetworkFailureWhileVerifyingCanBeRetried", async () => {
    verifyOtp.mockRejectedValue(new TypeError("Failed to fetch"))
    const user = userEvent.setup({ delay: null })
    render(<SignIn />)

    await user.type(screen.getByLabelText("Email"), "reader@example.com")
    await user.click(screen.getByRole("button", { name: "Send code" }))
    await user.type(await screen.findByLabelText("Code"), "123456")
    await user.click(screen.getByRole("button", { name: "Sign in" }))

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "That code didn't work. Check it, or send a new one.",
    )
    expect(screen.getByRole("button", { name: "Sign in" })).toBeEnabled()
  })

  describe('keepingItsPlaceWhenThePageIsDiscarded', () => {
    beforeEach(() => window.localStorage.clear())

    it('returningFromMailLandsOnTheCodeStepWithoutStoringTheCode', async () => {
      const user = userEvent.setup({ delay: null })
      const first = render(<SignIn />)
      await user.type(screen.getByLabelText('Email'), 'reader@example.com')
      await user.click(screen.getByRole('button', { name: 'Send code' }))
      await user.type(await screen.findByLabelText('Code'), '123456')
      const stored = window.localStorage.getItem(SIGNIN_KEY) ?? ''
      expect(stored).toContain('reader@example.com')
      expect(stored).not.toContain('123456')

      // iOS discarded the page while Mail was in front: a new page opens on the code step, with the code empty.
      first.unmount()
      render(<SignIn />)
      expect(screen.getByText('We sent a code to reader@example.com.')).toBeInTheDocument()
      expect(screen.getByLabelText('Code')).toHaveValue('')
    })

    it('theStoredStepExpiresAfterTenMinutes', () => {
      window.localStorage.setItem(SIGNIN_KEY, JSON.stringify({ email: 'reader@example.com', at: Date.now() - 11 * 60_000 }))
      render(<SignIn />)
      expect(screen.getByLabelText('Email')).toHaveValue('')
      expect(window.localStorage.getItem(SIGNIN_KEY)).toBeNull()
    })

    it('useAnotherEmailAndASuccessfulSignInClearIt', async () => {
      const user = userEvent.setup({ delay: null })
      verifyOtp.mockResolvedValue({ data: {}, error: null })
      const first = render(<SignIn />)
      await user.type(screen.getByLabelText('Email'), 'reader@example.com')
      await user.click(screen.getByRole('button', { name: 'Send code' }))
      await user.click(await screen.findByRole('button', { name: 'Use another email' }))
      expect(window.localStorage.getItem(SIGNIN_KEY)).toBeNull()

      await user.click(screen.getByRole('button', { name: 'Send code' }))
      await user.type(await screen.findByLabelText('Code'), '123456')
      await user.click(screen.getByRole('button', { name: 'Sign in' }))
      await waitFor(() => expect(window.localStorage.getItem(SIGNIN_KEY)).toBeNull())
      first.unmount()
    })
  })

  it('saysThatANewEmailCreatesAnAccount', () => {
    render(<SignIn />)
    expect(screen.getByText(/this creates your free account/i)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Privacy policy' }).getAttribute('href')).toMatch(/privacy\.html$/)
    expect(screen.queryByText(/email you use in the app/i)).not.toBeInTheDocument()
  })

  it('theWordingFitsAPhoneAsWellAsADesk', () => {
    render(<SignIn />)
    expect(screen.queryByText(/desk/i)).not.toBeInTheDocument()
  })
})
