import { fireEvent, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { Field } from '../book/Field'
import { useCommitOnHide } from './useCommitOnHide'

function Page({ onCommit }: { onCommit: (v: string) => boolean }) {
  useCommitOnHide()
  return <Field kind="text" label="Title" shown="Old" onCommit={onCommit} />
}

const hide = () => {
  Object.defineProperty(document, 'visibilityState', { configurable: true, value: 'hidden' })
  document.dispatchEvent(new Event('visibilitychange'))
}

afterEach(() => {
  Object.defineProperty(document, 'visibilityState', { configurable: true, value: 'visible' })
})

describe('leaving the page while typing', () => {
  it('aFieldBeingEditedIsCommittedWhenThePageIsHidden', async () => {
    const onCommit = vi.fn(() => true)
    render(<Page onCommit={onCommit} />)
    const box = screen.getByLabelText('Title')
    await userEvent.setup().clear(box)
    await userEvent.setup().type(box, 'New title')
    expect(onCommit).not.toHaveBeenCalled() // still being typed: no blur yet, as on an iPhone sent to the Home Screen

    hide()
    expect(onCommit).toHaveBeenCalledWith('New title')
  })

  it('pagehideCommitsToo', async () => {
    const onCommit = vi.fn(() => true)
    render(<Page onCommit={onCommit} />)
    const box = screen.getByLabelText('Title')
    await userEvent.setup().type(box, '!')
    fireEvent(window, new Event('pagehide'))
    expect(onCommit).toHaveBeenCalledWith('Old!')
  })

  it('aPageThatBecomesVisibleOrHasNoFieldFocusedChangesNothing', () => {
    const onCommit = vi.fn(() => true)
    render(<Page onCommit={onCommit} />)
    hide()
    document.dispatchEvent(new Event('visibilitychange'))
    expect(onCommit).not.toHaveBeenCalled()
  })
})
