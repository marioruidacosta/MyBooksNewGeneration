import { createContext, useContext, useSyncExternalStore } from 'react'
import type { Publisher } from './publisher'

/** The community publisher of the signed-in account; null while there is none. */
export const SharingContext = createContext<Publisher | null>(null)

const never = () => () => {}

/** Whether the community has this book; null when sharing isn't known (no publisher). */
export function useIsShared(id: string): boolean | null {
  const publisher = useContext(SharingContext)
  return useSyncExternalStore(publisher ? publisher.subscribe : never, () => (publisher ? publisher.isShared(id) : null))
}
