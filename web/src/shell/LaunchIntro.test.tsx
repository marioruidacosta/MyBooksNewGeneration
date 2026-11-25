import { act, fireEvent, render } from '@testing-library/react'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { LaunchIntro } from './LaunchIntro'

beforeEach(() => vi.useFakeTimers())
afterEach(() => vi.useRealTimers())

const after = (ms: number) => act(() => vi.advanceTimersByTime(ms))

describe('LaunchIntro', () => {
  it('showsTheWordmarkThenFadesIntoTheApp', () => {
    const { container } = render(<LaunchIntro reducedMotion={false} />)
    const intro = container.querySelector('.launch-intro')
    expect(intro).toHaveTextContent('MyBooks')
    // "My" comes in whole, then "Books" whole: two words, not seven letters.
    expect([...intro!.querySelectorAll('.launch-intro__word > span')].map((s) => s.textContent)).toEqual(['My', 'Books'])
    // Out of the way of screen readers: it says nothing the app doesn't.
    expect(intro).toHaveAttribute('aria-hidden', 'true')
    after(1499)
    expect(intro).not.toHaveClass('launch-intro--leaving')
    after(1)
    expect(intro).toHaveClass('launch-intro--leaving')
    after(350)
    expect(container).toBeEmptyDOMElement()
  })

  it('aTapOrAKeySkipsIt', () => {
    const { container } = render(<LaunchIntro reducedMotion={false} />)
    fireEvent.click(container.querySelector('.launch-intro')!)
    expect(container.querySelector('.launch-intro')).toHaveClass('launch-intro--leaving')
    after(350)
    expect(container).toBeEmptyDOMElement()

    const again = render(<LaunchIntro reducedMotion={false} />)
    fireEvent.keyDown(window, { key: 'Enter' })
    expect(again.container.querySelector('.launch-intro')).toHaveClass('launch-intro--leaving')
  })

  it('withReducedMotionItIsBrief', () => {
    const { container } = render(<LaunchIntro reducedMotion />)
    after(500)
    expect(container.querySelector('.launch-intro')).toHaveClass('launch-intro--leaving')
  })

  it('theLettersAreVisibleWhenAnimationsAreOff', () => {
    // Reduced motion turns every animation off: the letters' resting state must be the visible one.
    const css = readFileSync(resolve(process.cwd(), 'src/theme.css'), 'utf8')
    const letters = /\.launch-intro__word > span \{([^}]*)\}/.exec(css)?.[1] ?? ''
    expect(letters).toMatch(/animation:[^;]*backwards/)
    expect(letters).not.toMatch(/opacity:\s*0/)
  })
})
