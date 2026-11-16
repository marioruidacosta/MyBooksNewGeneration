import { createContext, useContext } from 'react'
import type { LibraryStore } from './store'

/** The signed-in person's library store; null while signed out or opening. */
export const StoreContext = createContext<LibraryStore | null>(null)

/** The store, for a page that is only shown to a signed-in person. */
export function useStore(): LibraryStore {
  const store = useContext(StoreContext)
  if (!store) throw new Error('useStore: no library is open')
  return store
}

/** The store when there is one (the page shell shows the save badge only then). */
export function useOptionalStore(): LibraryStore | null {
  return useContext(StoreContext)
}
