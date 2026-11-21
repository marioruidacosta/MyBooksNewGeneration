import { useCallback, useEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import { TOAST_MS, ToastContext, type ToastAction, type ToastApi, type ToastOptions } from './toastContext'

/** How many show at once: past this, the oldest goes. */
const MAX_TOASTS = 3

type Shown = { id: number; message: string; action?: ToastAction; focus?: boolean }

/** Whether the keys go to a text control (Ctrl+Z is then that control's own undo). */
function isTyping(target: EventTarget | null): boolean {
  if (!(target instanceof HTMLElement)) return false
  return target.isContentEditable || ['INPUT', 'TEXTAREA', 'SELECT'].includes(target.tagName)
}

/** The page's main heading, where focus goes when the element that had it is gone. */
function focusHeading() {
  const heading = document.querySelector<HTMLElement>('h1')
  if (!heading) return
  if (!heading.hasAttribute('tabindex')) heading.tabIndex = -1
  heading.focus()
}

/** One message. Its time runs out on its own, and stops while it is hovered or has focus. */
function ToastItem({
  toast, onDismiss, onRun,
}: {
  toast: Shown
  onDismiss: (id: number) => void
  onRun: (toast: Shown) => void
}) {
  const [hovered, setHovered] = useState(false)
  const [focused, setFocused] = useState(false)
  const held = hovered || focused
  const left = useRef(TOAST_MS)
  const root = useRef<HTMLDivElement>(null)
  const firstButton = useRef<HTMLButtonElement>(null)
  /** What had focus before the message took it. */
  const prior = useRef<Element | null>(null)

  useEffect(() => {
    if (!toast.focus) return
    prior.current = document.activeElement
    firstButton.current?.focus()
    // Once, when the message appears.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  /** When the message holds focus and is about to go: back to where it was, or the heading if that is gone. */
  const giveFocusBack = () => {
    if (!root.current?.contains(document.activeElement)) return
    const target = prior.current
    if (target instanceof HTMLElement && target !== document.body && target.isConnected && !root.current.contains(target)) target.focus()
    else focusHeading()
  }

  useEffect(() => {
    if (held) return
    const started = Date.now()
    const timer = setTimeout(() => onDismiss(toast.id), left.current)
    return () => {
      clearTimeout(timer)
      left.current = Math.max(0, left.current - (Date.now() - started))
    }
  }, [held, onDismiss, toast.id])

  return (
    <div
      className="toast"
      ref={root}
      onMouseEnter={() => setHovered(true)}
      onMouseLeave={() => setHovered(false)}
      onFocus={(e) => {
        if (prior.current === null && !e.currentTarget.contains(e.relatedTarget as Node | null)) {
          prior.current = e.relatedTarget as Element | null
        }
        setFocused(true)
      }}
      onBlur={(e) => {
        if (!e.currentTarget.contains(e.relatedTarget as Node | null)) setFocused(false)
      }}
    >
      <span>{toast.message}</span>
      {toast.action && (
        <button
          type="button"
          ref={firstButton}
          onClick={() => {
            giveFocusBack()
            onRun(toast)
          }}
        >
          {toast.action.label}
        </button>
      )}
      <button
        type="button"
        className="toast__dismiss"
        aria-label="Dismiss"
        ref={toast.action ? undefined : firstButton}
        onClick={() => {
          giveFocusBack()
          onDismiss(toast.id)
        }}
      >
        <span aria-hidden="true">×</span>
      </button>
    </div>
  )
}

/**
 * Short messages with an optional button (Undo), announced through a live region. Up to three show at once, the newest
 * at the bottom, each with its own Undo and its own [TOAST_MS]. The region is always on the page, so a message put
 * into it is read out.
 */
export function ToastProvider({ children, pinned }: { children: ReactNode; pinned?: ReactNode }) {
  const [shown, setShown] = useState<Shown[]>([])
  const next = useRef(0)

  const dismiss = useCallback((id: number) => setShown((list) => list.filter((t) => t.id !== id)), [])

  const show = useCallback((message: string, action?: ToastAction, options?: ToastOptions) => {
    const id = ++next.current
    setShown((list) => [...list, { id, message, action, focus: options?.focus }].slice(-MAX_TOASTS))
  }, [])

  const run = useCallback(
    (toast: Shown) => {
      dismiss(toast.id)
      try {
        toast.action?.run()
      } catch (e) {
        console.error('Undo failed', e)
        show("Couldn't undo.")
      }
    },
    [dismiss, show],
  )

  // Ctrl/Cmd+Z undoes the newest message that has an Undo, unless the keys are going to a text field.
  const latest = useRef<Shown[]>([])
  latest.current = shown
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (!(e.ctrlKey || e.metaKey) || e.shiftKey || e.altKey || e.key.toLowerCase() !== 'z') return
      if (isTyping(e.target)) return
      const newest = [...latest.current].reverse().find((t) => t.action)
      if (!newest) return
      e.preventDefault()
      run(newest)
    }
    document.addEventListener('keydown', onKey)
    return () => document.removeEventListener('keydown', onKey)
  }, [run])

  const api = useMemo<ToastApi>(() => ({ show }), [show])

  return (
    <ToastContext.Provider value={api}>
      {children}
      <div className="toast-region" role="status">
        {/* Pinned: something that stays above the messages in the same stack (the update notice), so they never overlap. */}
        {pinned}
        {shown.map((t) => (
          <ToastItem key={t.id} toast={t} onDismiss={dismiss} onRun={run} />
        ))}
      </div>
    </ToastContext.Provider>
  )
}
