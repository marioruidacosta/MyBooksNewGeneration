import { searchKey } from '../text/keys'

/** The app's cloth-binding tones (GeneratedCover.kt), as ARGB; light text reads on all of them. */
export const COVER_COLORS: readonly number[] = [
  0xff33506b, 0xff6b3a3a, 0xff2f5d50, 0xff5b4a6e, 0xff7a5c2e,
  0xff3e4a5c, 0xff6e4b5a, 0xff4a5d3a, 0xff7b4a2f, 0xff2e4e5e,
]

/** The colour of a title's generated cover: the same on every device (a fixed hash of the title, case and accents aside). */
export function coverColorIndex(title: string): number {
  let hash = 0
  for (const c of searchKey(title)) {
    // `for...of` walks code points; the app walks UTF-16 units, so feed those.
    for (let i = 0; i < c.length; i++) hash = (Math.imul(hash, 31) + c.charCodeAt(i)) & 0x7fffffff
  }
  return hash % COVER_COLORS.length
}

/** The title's cover colour as `#rrggbb`. */
export function coverColorCss(title: string): string {
  return `#${(COVER_COLORS[coverColorIndex(title)] & 0xffffff).toString(16).padStart(6, '0')}`
}
