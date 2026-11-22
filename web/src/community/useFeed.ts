import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { communityMessage } from './api'
import type { FeedCursor, FeedEntry, FeedPage } from './model'
import { groupFeed } from './text'

/** A paged feed: the first page for [key], "Show more", and one entry changed in place (a like). */
export function useFeed(load: (cursor: FeedCursor | null) => Promise<FeedPage>, key: string) {
  const [entries, setEntries] = useState<FeedEntry[]>([])
  const [next, setNext] = useState<FeedCursor | null>(null)
  const [loading, setLoading] = useState(true)
  const [loadingMore, setLoadingMore] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [attempt, setAttempt] = useState(0)
  const latest = useRef(load)
  latest.current = load
  // Bumped whenever the list starts again, so a "Show more" still on its way can't add to the wrong list.
  const run = useRef(0)

  useEffect(() => {
    const mine = ++run.current
    setLoading(true)
    setLoadingMore(false)
    setError(null)
    setEntries([])
    setNext(null)
    latest.current(null).then(
      (page) => {
        if (run.current !== mine) return
        setEntries(page.entries)
        setNext(page.next)
        setLoading(false)
      },
      (e: unknown) => {
        if (run.current !== mine) return
        setError(communityMessage(e))
        setLoading(false)
      },
    )
    // Ends this run; the counter is shared with "Show more", so it is the ref that is meant.
    const counter = run
    return () => {
      counter.current++
    }
  }, [key, attempt])

  const more = useCallback(() => {
    if (!next || loadingMore) return
    const mine = run.current
    setLoadingMore(true)
    setError(null)
    latest.current(next).then(
      (page) => {
        if (run.current !== mine) return
        // An entry can come twice when new ones arrive between pages.
        setEntries((old) => {
          const seen = new Set(old.map((e) => e.id))
          return [...old, ...page.entries.filter((e) => !seen.has(e.id))]
        })
        setNext(page.next)
        setLoadingMore(false)
      },
      (e: unknown) => {
        if (run.current !== mine) return
        setError(communityMessage(e))
        setLoadingMore(false)
      },
    )
  }, [next, loadingMore])

  const change = useCallback((entry: FeedEntry) => {
    setEntries((old) => old.map((e) => (e.id === entry.id ? entry : e)))
  }, [])
  const reload = useCallback(() => setAttempt((n) => n + 1), [])
  const items = useMemo(() => groupFeed(entries), [entries])
  return { items, count: entries.length, hasMore: next !== null, loading, loadingMore, error, more, change, reload }
}
