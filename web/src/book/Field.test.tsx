import { fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { Field } from './Field'
import { parseWhole } from './parse'

/** A number box as a browser reports partly invalid text ("12e", "-"): empty value, but `badInput`. */
function typeAs(input: HTMLInputElement, bad: boolean) {
  Object.defineProperty(input, 'validity', { configurable: true, value: { badInput: bad } })
  fireEvent.change(input, { target: { value: '' } })
  fireEvent.blur(input)
}

describe('Field numbers', () => {
  it('aTypoInANumberKeepsTheValue', () => {
    const onCommit = vi.fn()
    render(<Field kind="number" label="Pages" shown="310" onCommit={onCommit} />)
    const input = screen.getByLabelText('Pages') as HTMLInputElement

    typeAs(input, true)

    expect(onCommit).not.toHaveBeenCalled()
    expect(input).toHaveValue(310)
    expect(parseWhole('12e')).toBeUndefined()
    expect(parseWhole('-')).toBeUndefined()
  })

  it('clearingANumberClearsIt', () => {
    const onCommit = vi.fn()
    render(<Field kind="number" label="Pages" shown="310" onCommit={onCommit} />)

    typeAs(screen.getByLabelText('Pages') as HTMLInputElement, false)

    expect(onCommit).toHaveBeenCalledTimes(1)
    expect(onCommit).toHaveBeenCalledWith('')
    expect(parseWhole('')).toBeNull()
  })
})
