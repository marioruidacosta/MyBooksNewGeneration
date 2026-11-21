import { useSyncExternalStore } from 'react'

/**
 * A phone: a narrow screen, or a touch phone on its side (short, so its width alone would pass for a laptop).
 * theme.css repeats these breakpoints in its phone rules.
 */
export const PHONE_QUERY = '(max-width: 52rem), (pointer: coarse) and (max-height: 30rem)'

/** Whether the page is on a phone now; false where the browser cannot say. */
export function isPhone(): boolean {
  return typeof window !== 'undefined' && typeof window.matchMedia === 'function' && window.matchMedia(PHONE_QUERY).matches
}

function subscribe(onChange: () => void): () => void {
  if (typeof window === 'undefined' || typeof window.matchMedia !== 'function') return () => {}
  const list = window.matchMedia(PHONE_QUERY)
  list.addEventListener('change', onChange)
  return () => list.removeEventListener('change', onChange)
}

/** [isPhone], kept current as the window turns or resizes. */
export function usePhone(): boolean {
  return useSyncExternalStore(subscribe, isPhone, () => false)
}
