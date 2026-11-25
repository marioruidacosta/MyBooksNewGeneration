import { useEffect, useState, type CSSProperties } from 'react'

/** How long the two words and the shelf line take before the intro fades, and the fade. */
const SHOW_MS = 1500
const BRIEF_MS = 500
const FADE_MS = 350

const prefersReducedMotion = () =>
  typeof window !== 'undefined' && typeof window.matchMedia === 'function' && window.matchMedia('(prefers-reduced-motion: reduce)').matches

/**
 * Opening the app: "My" rises in, then "Books", a shelf line draws under them, then it all fades into the app. A tap
 * or a key skips it; with reduced motion the wordmark just shows briefly.
 */
export function LaunchIntro({ reducedMotion = prefersReducedMotion() }: { reducedMotion?: boolean }) {
  const [phase, setPhase] = useState<'showing' | 'leaving' | 'gone'>('showing')

  useEffect(() => {
    if (phase === 'gone') return
    const t = setTimeout(() => setPhase(phase === 'showing' ? 'leaving' : 'gone'), phase === 'showing' ? (reducedMotion ? BRIEF_MS : SHOW_MS) : FADE_MS)
    return () => clearTimeout(t)
  }, [phase, reducedMotion])

  useEffect(() => {
    if (phase !== 'showing') return
    const skip = () => setPhase('leaving')
    window.addEventListener('keydown', skip)
    return () => window.removeEventListener('keydown', skip)
  }, [phase])

  if (phase === 'gone') return null
  return (
    <div
      className={`launch-intro${phase === 'leaving' ? ' launch-intro--leaving' : ''}`}
      aria-hidden="true"
      onClick={() => setPhase('leaving')}
    >
      <div className="launch-intro__inner">
        <span className="wordmark launch-intro__word">
          {['My', 'Books'].map((word, i) => (
            <span key={word} style={{ '--i': i } as CSSProperties}>
              {word}
            </span>
          ))}
        </span>
        <span className="launch-intro__shelf" />
      </div>
    </div>
  )
}
