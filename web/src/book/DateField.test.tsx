import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { DateField } from './DateField'

afterEach(() => {
  // @ts-expect-error restoring jsdom's lack of showPicker.
  delete HTMLInputElement.prototype.showPicker
})

describe('DateField calendar button', () => {
  it('theCalendarInputIsFocusedEvenWhenShowPickerDoesNothing', async () => {
    // Some browsers define showPicker and silently do nothing (as iOS Safari may); the native picker opens on focus.
    const showPicker = vi.fn()
    Object.defineProperty(HTMLInputElement.prototype, 'showPicker', { configurable: true, value: showPicker })
    render(<DateField label="Read on" value={null} onCommit={() => true} />)
    await userEvent.setup().click(screen.getByRole('button', { name: 'Pick a date for Read on' }))
    expect(showPicker).toHaveBeenCalled()
    expect(document.activeElement).toBe(document.querySelector('input[type="date"]'))
  })
})
