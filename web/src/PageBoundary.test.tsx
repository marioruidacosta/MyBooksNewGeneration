import { Suspense, lazy } from 'react'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { PageBoundary } from './PageBoundary'
import { StoreContext } from './sync/StoreContext'
import type { LibraryStore } from './sync/store'

const failing = () => lazy(() => Promise.reject<{ default: () => null }>(new Error('boom')))

afterEach(() => {
  vi.unstubAllGlobals()
  sessionStorage.clear()
})

describe('PageBoundary', () => {
  it('aPageThatFailsToLoadOffersReload', async () => {
    const reload = vi.fn()
    vi.stubGlobal('location', { ...window.location, reload })
    vi.spyOn(console, 'error').mockImplementation(() => {})
    const Page = failing()
    render(
      <PageBoundary>
        <Suspense fallback={<p>Loading…</p>}>
          <Page />
        </Suspense>
      </PageBoundary>,
    )
    expect(await screen.findByText("This page couldn't load. The site may have been updated.")).toBeInTheDocument()
    await userEvent.setup().click(screen.getByRole('button', { name: 'Reload' }))
    expect(reload).toHaveBeenCalledTimes(1)
  })

  it('aChunkErrorReloadsOnceThenShowsTheMessage', async () => {
    const reload = vi.fn()
    vi.stubGlobal('location', { ...window.location, reload })
    vi.spyOn(console, 'error').mockImplementation(() => {})
    const chunk = () =>
      lazy(() => Promise.reject<{ default: () => null }>(new TypeError('Failed to fetch dynamically imported module: /x.js')))
    for (const Page of [chunk(), chunk()]) {
      render(
        <PageBoundary>
          <Suspense fallback={null}>
            <Page />
          </Suspense>
        </PageBoundary>,
      )
    }
    expect((await screen.findAllByRole('button', { name: 'Reload' })).length).toBeGreaterThan(0)
    // The first failure reloaded by itself; the second, in the same session, did not.
    expect(reload).toHaveBeenCalledTimes(1)
  })

  it('aChunkErrorDoesNotReloadByItselfOverUnsavedChanges', async () => {
    const reload = vi.fn()
    vi.stubGlobal('location', { ...window.location, reload })
    vi.spyOn(console, 'error').mockImplementation(() => {})
    const Page = lazy(() => Promise.reject<{ default: () => null }>(new TypeError('Failed to fetch dynamically imported module: /x.js')))
    const store = { hasPendingWrites: () => true } as unknown as LibraryStore
    render(
      <StoreContext.Provider value={store}>
        <PageBoundary>
          <Suspense fallback={null}>
            <Page />
          </Suspense>
        </PageBoundary>
      </StoreContext.Provider>,
    )
    // The Reload button is offered, and the person decides; nothing was reloaded, nor marked as reloaded.
    await userEvent.setup().click(await screen.findByRole('button', { name: 'Reload' }))
    expect(reload).toHaveBeenCalledTimes(1)
    expect(sessionStorage.getItem('pageBoundary.reloaded')).toBeNull()
  })
})
