import { useCallback, useEffect, useRef, useState } from 'react'

/** How long the "Saved" tick stays. */
export const SAVED_MS = 2000

/** Whether a field has just saved, and a way to say it did; the tick goes by itself after about 2 s. */
export function useSaved(): [saved: boolean, flash: () => void] {
  const [saved, setSaved] = useState(false)
  const timer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined)
  const flash = useCallback(() => {
    clearTimeout(timer.current)
    setSaved(true)
    timer.current = setTimeout(() => setSaved(false), SAVED_MS)
  }, [])
  useEffect(() => () => clearTimeout(timer.current), [])
  return [saved, flash]
}
