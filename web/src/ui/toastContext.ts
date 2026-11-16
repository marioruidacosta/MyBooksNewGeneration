import { createContext, useContext } from 'react'

/** How long a message stays before it goes away by itself. */
export const TOAST_MS = 8_000

/** `focus` moves focus to the message's button (a keyboard delete leaves nothing else to reach Undo with). */
export type ToastOptions = { focus?: boolean }

export type ToastAction = { label: string; run: () => void }

export type ToastApi = {
  /** Shows [message], with an optional button; a new message replaces the one showing. */
  show: (message: string, action?: ToastAction, options?: ToastOptions) => void
}

// Without a provider (a page on its own, in a test) a message goes nowhere.
const NONE: ToastApi = { show: () => {} }
export const ToastContext = createContext<ToastApi>(NONE)

export const useToast = (): ToastApi => useContext(ToastContext)
