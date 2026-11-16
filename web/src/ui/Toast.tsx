import { useCallback, useEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import { TOAST_MS, ToastContext, type ToastAction, type ToastApi } from './toastContext'

type Shown = { id: number; message: string; action?: ToastAction }

/**
 * A short message with an optional button (Undo), announced through a live region and gone after [TOAST_MS]. The
 * region is always on the page, so a message put into it is read out.
 */
export function ToastProvider({ children }: { children: ReactNode }) {
  const [shown, setShown] = useState<Shown | null>(null)
  const next = useRef(0)
  const timer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined)

  const close = useCallback(() => {
    clearTimeout(timer.current)
    setShown(null)
  }, [])

  const show = useCallback((message: string, action?: ToastAction) => {
    clearTimeout(timer.current)
    const id = ++next.current
    setShown({ id, message, action })
    timer.current = setTimeout(() => setShown((s) => (s?.id === id ? null : s)), TOAST_MS)
  }, [])

  useEffect(() => () => clearTimeout(timer.current), [])

  const api = useMemo<ToastApi>(() => ({ show }), [show])

  return (
    <ToastContext.Provider value={api}>
      {children}
      <div className="toast-region" role="status">
        {shown && (
          <div className="toast" key={shown.id}>
            <span>{shown.message}</span>
            {shown.action && (
              <button
                type="button"
                onClick={() => {
                  const { run } = shown.action as ToastAction
                  close()
                  run()
                }}
              >
                {shown.action.label}
              </button>
            )}
          </div>
        )}
      </div>
    </ToastContext.Provider>
  )
}
