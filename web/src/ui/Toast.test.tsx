import { act, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ToastProvider } from './Toast'
import { useToast } from './toastContext'

function Trigger({ onUndo }: { onUndo: () => void }) {
  const toast = useToast()
  return (
    <>
      <button type="button" onClick={() => toast.show('Deleted 2 books.', { label: 'Undo', run: onUndo })}>
        Go
      </button>
      <button type="button" onClick={() => toast.show('Saved.')}>
        Plain
      </button>
    </>
  )
}

afterEach(() => vi.useRealTimers())

describe('Toast', () => {
  it('announcesTheMessageWithUndoAndDismissesItselfAfterEightSeconds', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime })
    render(
      <ToastProvider>
        <Trigger onUndo={() => {}} />
      </ToastProvider>,
    )
    await user.click(screen.getByRole('button', { name: 'Go' }))
    expect(screen.getByRole('status')).toHaveTextContent('Deleted 2 books.')
    expect(screen.getByRole('button', { name: 'Undo' })).toBeInTheDocument()

    act(() => void vi.advanceTimersByTime(7_000))
    expect(screen.getByRole('status')).toHaveTextContent('Deleted 2 books.')
    act(() => void vi.advanceTimersByTime(1_500))
    expect(screen.getByRole('status')).not.toHaveTextContent('Deleted')
    expect(screen.queryByRole('button', { name: 'Undo' })).not.toBeInTheDocument()
  })

  it('undoRunsTheActionOnceAndClosesTheToast', async () => {
    const undo = vi.fn()
    const user = userEvent.setup()
    render(
      <ToastProvider>
        <Trigger onUndo={undo} />
      </ToastProvider>,
    )
    await user.click(screen.getByRole('button', { name: 'Go' }))
    await user.click(screen.getByRole('button', { name: 'Undo' }))
    expect(undo).toHaveBeenCalledTimes(1)
    expect(screen.queryByRole('button', { name: 'Undo' })).not.toBeInTheDocument()
  })

  it('aMessageWithoutAnActionHasNoUndoButton', async () => {
    const user = userEvent.setup()
    render(
      <ToastProvider>
        <Trigger onUndo={() => {}} />
      </ToastProvider>,
    )
    await user.click(screen.getByRole('button', { name: 'Plain' }))
    expect(screen.getByRole('status')).toHaveTextContent('Saved.')
    expect(screen.queryByRole('button', { name: 'Undo' })).not.toBeInTheDocument()
  })
})
