import { PHONE_QUERY } from './ui/usePhone'

/** Makes the page think it is on a phone (or not): `matchMedia` says so for the phone query, and no for any other. */
export function setPhone(phone: boolean): void {
  window.matchMedia = ((query: string) => ({
    matches: phone && query === PHONE_QUERY,
    media: query,
    onchange: null,
    addEventListener: () => {},
    removeEventListener: () => {},
    addListener: () => {},
    removeListener: () => {},
    dispatchEvent: () => false,
  })) as typeof window.matchMedia
}

/** Back to a browser with no `matchMedia` at all, as jsdom has it. */
export function clearMedia(): void {
  // @ts-expect-error jsdom has no matchMedia: removing it restores that.
  delete window.matchMedia
}
