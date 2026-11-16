import { useEffect, useRef, useState } from 'react'
import type { LibraryStore } from './store'

type Opened = { userId: string; store: LibraryStore | null; error: string | null }

/**
 * One store per signed-in user id: opened (cache, then a first pass) when the id appears, closed when it goes (sign-out)
 * or changes (another account), so a store never serves a different account. `store` is null until it has opened.
 */
export function useLibraryStore(
  userId: string | null,
  create: (userId: string) => LibraryStore,
): { store: LibraryStore | null; error: string | null } {
  const [opened, setOpened] = useState<Opened | null>(null)
  const makeStore = useRef(create)
  makeStore.current = create

  useEffect(() => {
    if (userId === null) return
    const store = makeStore.current(userId)
    let live = true
    store.load().then(
      () => {
        if (live) setOpened({ userId, store, error: null })
      },
      (e: unknown) => {
        console.error('LibraryStore: could not open', e)
        if (live) setOpened({ userId, store: null, error: e instanceof Error ? e.message : String(e) })
        void store.close()
      },
    )
    return () => {
      live = false
      void store.close()
    }
  }, [userId])

  // Signed out: forget the closed store, so the same user signing back in opens a fresh one.
  if (userId === null && opened !== null) setOpened(null)

  // What was opened for another account (or before signing out) is never handed out.
  const current = opened !== null && opened.userId === userId ? opened : null
  return { store: current?.store ?? null, error: current?.error ?? null }
}

/** Asks the browser to confirm closing the tab while the store has changes not yet safe (see `hasPendingWrites`), or the community publisher has changes not yet sent. */
export function useWarnBeforeClosing(store: LibraryStore | null, publisher?: { hasPending(): boolean } | null): void {
  useEffect(() => {
    if (!store) return
    const warn = (event: BeforeUnloadEvent) => {
      if (!store.hasPendingWrites() && !publisher?.hasPending()) return
      event.preventDefault()
      event.returnValue = ''
    }
    window.addEventListener('beforeunload', warn)
    return () => window.removeEventListener('beforeunload', warn)
  }, [store, publisher])
}
