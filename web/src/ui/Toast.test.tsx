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

  it('aSecondToastKeepsTheFirstUndo', async () => {
    const first = vi.fn()
    const second = vi.fn()
    function Two() {
      const toast = useToast()
      return (
        <>
          <button type="button" onClick={() => toast.show('First.', { label: 'Undo', run: first })}>
            One
          </button>
          <button type="button" onClick={() => toast.show('Second.', { label: 'Undo', run: second })}>
            Two
          </button>
          <button type="button" onClick={() => toast.show('Third.')}>
            Three
          </button>
          <button type="button" onClick={() => toast.show('Fourth.')}>
            Four
          </button>
        </>
      )
    }
    const user = userEvent.setup()
    render(
      <ToastProvider>
        <Two />
      </ToastProvider>,
    )
    await user.click(screen.getByRole('button', { name: 'One' }))
    await user.click(screen.getByRole('button', { name: 'Two' }))
    const text = () => screen.getByRole('status').textContent
    // Both are there, the newest last, and each has its own Undo.
    expect(text()).toBe('First.Undo×Second.Undo×')
    const undos = screen.getAllByRole('button', { name: 'Undo' })
    expect(undos).toHaveLength(2)
    await user.click(undos[0])
    expect(first).toHaveBeenCalledTimes(1)
    expect(second).not.toHaveBeenCalled()
    expect(text()).toBe('Second.Undo×')

    // At most three: when there are more, the oldest goes.
    await user.click(screen.getByRole('button', { name: 'Three' }))
    await user.click(screen.getByRole('button', { name: 'Four' }))
    expect(text()).toBe('Second.Undo×Third.×Fourth.×')
    await user.click(screen.getByRole('button', { name: 'Three' }))
    expect(text()).toBe('Third.×Fourth.×Third.×')
  })

  it('toastPausesWhileFocused', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime })
    render(
      <ToastProvider>
        <Trigger onUndo={() => {}} />
      </ToastProvider>,
    )
    await user.click(screen.getByRole('button', { name: 'Go' }))
    act(() => screen.getByRole('button', { name: 'Undo' }).focus())
    act(() => void vi.advanceTimersByTime(30_000))
    expect(screen.getByRole('status')).toHaveTextContent('Deleted 2 books.')

    // Hovering keeps it too; leaving starts the rest of the time.
    await user.hover(screen.getByRole('button', { name: 'Undo' }))
    act(() => screen.getByRole('button', { name: 'Undo' }).blur())
    act(() => void vi.advanceTimersByTime(30_000))
    expect(screen.getByRole('status')).toHaveTextContent('Deleted 2 books.')
    await user.unhover(screen.getByRole('button', { name: 'Undo' }))
    act(() => void vi.advanceTimersByTime(8_500))
    expect(screen.getByRole('status')).not.toHaveTextContent('Deleted')
  })

  it('aFailingUndoSaysSoInsteadOfThrowing', async () => {
    const user = userEvent.setup()
    render(
      <ToastProvider>
        <Trigger
          onUndo={() => {
            throw new Error('boom')
          }}
        />
      </ToastProvider>,
    )
    await user.click(screen.getByRole('button', { name: 'Go' }))
    await user.click(screen.getByRole('button', { name: 'Undo' }))
    expect(screen.getByRole('status')).toHaveTextContent("Couldn't undo.")
    expect(screen.queryByRole('button', { name: 'Undo' })).not.toBeInTheDocument()
  })

  it('aToastCanBeDismissedAndALogsAFailingUndo', async () => {
    const error = vi.spyOn(console, 'error').mockImplementation(() => {})
    const user = userEvent.setup()
    render(
      <ToastProvider>
        <Trigger
          onUndo={() => {
            throw new Error('boom')
          }}
        />
      </ToastProvider>,
    )
    await user.click(screen.getByRole('button', { name: 'Plain' }))
    await user.click(screen.getByRole('button', { name: 'Dismiss' }))
    expect(screen.getByRole('status')).not.toHaveTextContent('Saved.')

    await user.click(screen.getByRole('button', { name: 'Go' }))
    await user.click(screen.getByRole('button', { name: 'Undo' }))
    expect(error).toHaveBeenCalled()
    error.mockRestore()
  })

  it('ctrlZUndoesTheLastAction', async () => {
    const user = userEvent.setup()
    const first = vi.fn()
    const second = vi.fn()
    function Two() {
      const toast = useToast()
      return (
        <>
          <button type="button" onClick={() => toast.show('One.', { label: 'Undo', run: first })}>
            One
          </button>
          <button type="button" onClick={() => toast.show('Two.', { label: 'Undo', run: second })}>
            Two
          </button>
          <input aria-label="Text" />
        </>
      )
    }
    render(
      <ToastProvider>
        <Two />
      </ToastProvider>,
    )
    await user.click(screen.getByRole('button', { name: 'One' }))
    await user.click(screen.getByRole('button', { name: 'Two' }))

    // In a text field, Ctrl+Z is the field's own.
    await user.click(screen.getByLabelText('Text'))
    await user.keyboard('{Control>}z{/Control}')
    expect(second).not.toHaveBeenCalled()

    // Elsewhere it undoes the newest, then the one before.
    await user.click(screen.getByRole('button', { name: 'Two' }))
    second.mockClear()
    await user.keyboard('{Control>}z{/Control}')
    expect(second).toHaveBeenCalledTimes(1)
    await user.keyboard('{Control>}z{/Control}')
    expect(second).toHaveBeenCalledTimes(2)
    await user.keyboard('{Control>}z{/Control}')
    expect(first).toHaveBeenCalledTimes(1)
    await user.keyboard('{Control>}z{/Control}')
    expect(first).toHaveBeenCalledTimes(1)
  })

  it('undoOrDismissReturnsFocusToWhereItWas', async () => {
    const user = userEvent.setup()
    function Back() {
      const toast = useToast()
      return (
        <>
          <h1 tabIndex={-1}>Page</h1>
          <button type="button" onClick={() => toast.show('Done.', { label: 'Undo', run: () => {} }, { focus: true })}>
            Go
          </button>
        </>
      )
    }
    render(
      <ToastProvider>
        <Back />
      </ToastProvider>,
    )
    await user.click(screen.getByRole('button', { name: 'Go' }))
    expect(screen.getByRole('button', { name: 'Undo' })).toHaveFocus()
    await user.click(screen.getByRole('button', { name: 'Undo' }))
    expect(screen.getByRole('button', { name: 'Go' })).toHaveFocus()

    await user.click(screen.getByRole('button', { name: 'Go' }))
    await user.click(screen.getByRole('button', { name: 'Dismiss' }))
    expect(screen.getByRole('button', { name: 'Go' })).toHaveFocus()
  })
})
