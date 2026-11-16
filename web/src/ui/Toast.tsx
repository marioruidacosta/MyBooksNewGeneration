import { useCallback, useEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import { TOAST_MS, ToastContext, type ToastAction, type ToastApi } from './toastContext'

/** How many show at once: past this, the oldest goes. */
const MAX_TOASTS = 3

type Shown = { id: number; message: string; action?: ToastAction }

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
      onMouseEnter={() => setHovered(true)}
      onMouseLeave={() => setHovered(false)}
      onFocus={() => setFocused(true)}
      onBlur={(e) => {
        if (!e.currentTarget.contains(e.relatedTarget as Node | null)) setFocused(false)
      }}
    >
      <span>{toast.message}</span>
      {toast.action && (
        <button type="button" onClick={() => onRun(toast)}>
          {toast.action.label}
        </button>
      )}
      <button type="button" className="toast__dismiss" aria-label="Dismiss" onClick={() => onDismiss(toast.id)}>
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
export function ToastProvider({ children }: { children: ReactNode }) {
  const [shown, setShown] = useState<Shown[]>([])
  const next = useRef(0)

  const dismiss = useCallback((id: number) => setShown((list) => list.filter((t) => t.id !== id)), [])

  const show = useCallback((message: string, action?: ToastAction) => {
    const id = ++next.current
    setShown((list) => [...list, { id, message, action }].slice(-MAX_TOASTS))
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

  const api = useMemo<ToastApi>(() => ({ show }), [show])

  return (
    <ToastContext.Provider value={api}>
      {children}
      <div className="toast-region" role="status">
        {shown.map((t) => (
          <ToastItem key={t.id} toast={t} onDismiss={dismiss} onRun={run} />
        ))}
      </div>
    </ToastContext.Provider>
  )
}
