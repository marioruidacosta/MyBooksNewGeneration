import { useCallback, useEffect, useRef, useState } from 'react'
import { communityMessage } from './api'

/** What [load] returned, kept current when [key] changes; a stale answer is ignored. */
export function useLoaded<T>(load: () => Promise<T>, key: string) {
  const [state, setState] = useState<{ key: string; data: T | null; error: string | null; loading: boolean }>({
    key, data: null, error: null, loading: true,
  })
  const [attempt, setAttempt] = useState(0)
  const latest = useRef(load)
  latest.current = load

  useEffect(() => {
    let live = true
    setState((s) => ({ key, data: s.key === key ? s.data : null, error: null, loading: true }))
    latest.current().then(
      (data) => {
        if (live) setState({ key, data, error: null, loading: false })
      },
      (e: unknown) => {
        if (live) setState((s) => ({ key, data: s.key === key ? s.data : null, error: communityMessage(e), loading: false }))
      },
    )
    return () => {
      live = false
    }
  }, [key, attempt])

  const reload = useCallback(() => setAttempt((n) => n + 1), [])
  const setData = useCallback((update: (data: T | null) => T | null) => {
    setState((s) => ({ ...s, data: update(s.data) }))
  }, [])
  const current = state.key === key ? state : { key, data: null, error: null, loading: true }
  return { data: current.data, error: current.error, loading: current.loading, reload, setData }
}
