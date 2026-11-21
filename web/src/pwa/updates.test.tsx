import { act, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { UpdateNotice } from './UpdateNotice'
import { UpdateWatcher } from './updates'

class FakeWorker extends EventTarget {
  state = 'installing'
  postMessage = vi.fn()
}
class FakeRegistration extends EventTarget {
  waiting: FakeWorker | null = null
  installing: FakeWorker | null = null
  update = vi.fn(() => Promise.resolve())
}
class FakeContainer extends EventTarget {
  controller: object | null = {}
  registration = new FakeRegistration()
  register = vi.fn(() => Promise.resolve(this.registration))
}

/** A page that already runs one version, and a second one that finishes installing. */
function setup() {
  const container = new FakeContainer()
  const reload = vi.fn()
  const watcher = new UpdateWatcher(container as unknown as ServiceWorkerContainer, reload)
  watcher.watch(container.registration as unknown as ServiceWorkerRegistration)
  const install = () => {
    const worker = new FakeWorker()
    container.registration.installing = worker
    container.registration.dispatchEvent(new Event('updatefound'))
    worker.state = 'installed'
    act(() => void worker.dispatchEvent(new Event('statechange')))
    return worker
  }
  return { container, reload, watcher, install }
}

describe('a new version of the site', () => {
  it('aNewVersionOffersReloadAndNeverReloadsAlone', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    const { container, reload, watcher, install } = setup()
    render(<UpdateNotice watcher={watcher} />)
    expect(screen.queryByText(/New version available/)).not.toBeInTheDocument()

    const worker = install()
    expect(await screen.findByText('New version available.')).toBeInTheDocument()
    // It waits: not told to take over, and the page is not reloaded, however long it is left (or if the worker changes).
    await act(() => vi.advanceTimersByTimeAsync(10 * 60_000))
    act(() => void container.dispatchEvent(new Event('controllerchange')))
    expect(worker.postMessage).not.toHaveBeenCalled()
    expect(reload).not.toHaveBeenCalled()

    // Only the person's Reload applies it, and the page reloads once the new version has taken over.
    await userEvent.setup({ advanceTimers: vi.advanceTimersByTime }).click(screen.getByRole('button', { name: 'Reload' }))
    await vi.waitFor(() => expect(worker.postMessage).toHaveBeenCalledWith({ type: 'SKIP_WAITING' }))
    expect(reload).not.toHaveBeenCalled()
    container.dispatchEvent(new Event('controllerchange'))
    expect(reload).toHaveBeenCalledTimes(1)
    vi.useRealTimers()
  })

  it('theFirstInstallIsNotAnUpdate', () => {
    const { container, watcher, install } = setup()
    container.controller = null
    install()
    expect(watcher.available).toBe(false)
  })

  it('aWaitingVersionFoundAtStartIsOfferedToo', () => {
    const container = new FakeContainer()
    container.registration.waiting = new FakeWorker()
    const watcher = new UpdateWatcher(container as unknown as ServiceWorkerContainer, vi.fn())
    watcher.watch(container.registration as unknown as ServiceWorkerRegistration)
    expect(watcher.available).toBe(true)
  })

  it('reloadWaitsForUnsentChanges', async () => {
    const { watcher, install } = setup()
    const worker = install()
    let saving = true
    let unsent = false
    const user = userEvent.setup()
    render(<UpdateNotice watcher={watcher} store={{ hasPendingWrites: () => saving }} publisher={{ hasPending: () => unsent }} />)

    await user.click(await screen.findByRole('button', { name: 'Reload' }))
    expect(await screen.findByText('Still saving your changes. Try again in a moment.')).toBeInTheDocument()
    expect(worker.postMessage).not.toHaveBeenCalled()

    // The community publisher's unsent changes hold it back too.
    saving = false
    unsent = true
    await user.click(screen.getByRole('button', { name: 'Reload' }))
    expect(worker.postMessage).not.toHaveBeenCalled()

    unsent = false
    await user.click(screen.getByRole('button', { name: 'Reload' }))
    await vi.waitFor(() => expect(worker.postMessage).toHaveBeenCalledWith({ type: 'SKIP_WAITING' }))
  })

  it('leavingTheFieldSavesWhatWasTypedBeforeTheReloadIsChecked', async () => {
    const { watcher, install } = setup()
    const worker = install()
    const user = userEvent.setup()
    let saving = false
    const field = document.createElement('input')
    document.body.append(field)
    // The field saves on blur, as the book page's fields do: that makes a write pending.
    field.addEventListener('blur', () => (saving = true))
    field.focus()
    render(<UpdateNotice watcher={watcher} store={{ hasPendingWrites: () => saving }} />)
    await user.click(await screen.findByRole('button', { name: 'Reload' }))
    expect(saving).toBe(true)
    expect(worker.postMessage).not.toHaveBeenCalled()
    field.remove()
  })
})
