import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'

const signInWithOtp = vi.fn()
const verifyOtp = vi.fn()

vi.mock('../supabase', () => ({
  supabase: { auth: { signInWithOtp: (a: unknown) => signInWithOtp(a), verifyOtp: (a: unknown) => verifyOtp(a) } },
}))

import { SignIn } from './SignIn'

describe('SignIn', () => {
  beforeEach(() => {
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
      options: { shouldCreateUser: false },
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
})
