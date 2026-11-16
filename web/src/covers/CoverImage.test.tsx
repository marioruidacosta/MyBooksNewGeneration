import { act, fireEvent, render, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { CoverImage } from './CoverImage'
import { CoverContext, type CoverStorage } from './storage'

let counter = 0

/** A storage whose every signed link is new; `calls` lists the paths of each request. */
function fakeStorage() {
  const createSignedUrls = vi.fn(async (paths: string[]) => ({
    data: paths.map((path) => ({ path, signedUrl: `https://signed.example/${path}?n=${++counter}`, error: null })),
    error: null,
  }))
  const storage: CoverStorage = { from: () => ({ upload: vi.fn(), createSignedUrls }) }
  return { storage, createSignedUrls }
}

/** The photo itself (the generated cover has the same role and name, and shows until the link arrives). */
const photo = () => waitFor(() => {
  const img = document.querySelector('img')
  expect(img).not.toBeNull()
  return img as HTMLImageElement
})

const book = { id: 'b1', coverUrl: 'photo', title: 'Blindness', authors: 'Saramago' }

afterEach(() => vi.useRealTimers())

describe('CoverImage', () => {
  it('anExpiredCoverIsSignedAgain', async () => {
    const { storage, createSignedUrls } = fakeStorage()
    render(
      <CoverContext.Provider value={{ owner: 'u-expired-1', storage }}>
        <CoverImage book={book} />
      </CoverContext.Provider>,
    )
    const img = await photo()
    const first = img.getAttribute('src')
    expect(createSignedUrls).toHaveBeenCalledTimes(1)

    // The link has expired: the picture fails to load. It is signed again once, and shown.
    fireEvent.error(img)
    await waitFor(() => expect(createSignedUrls).toHaveBeenCalledTimes(2))
    await waitFor(() => expect(document.querySelector('img')?.getAttribute('src')).not.toBe(first))

    // And when even the new link fails, the generated cover shows instead (no endless retries).
    fireEvent.error(document.querySelector('img') as HTMLImageElement)
    await waitFor(() => expect(document.querySelector('img')).toBeNull())
    expect(createSignedUrls).toHaveBeenCalledTimes(2)
  })

  it('aCoverThatFailsAgainFallsBackToTheGeneratedOne', async () => {
    // The storage signs the very same link every time: a retry can't change what the picture loads.
    const createSignedUrls = vi.fn(async (paths: string[]) => ({
      data: paths.map((path) => ({ path, signedUrl: `https://signed.example/${path}?same`, error: null })),
      error: null,
    }))
    const storage: CoverStorage = { from: () => ({ upload: vi.fn(), createSignedUrls }) }
    render(
      <CoverContext.Provider value={{ owner: 'u-same-1', storage }}>
        <CoverImage book={book} />
      </CoverContext.Provider>,
    )
    const img = await photo()
    fireEvent.error(img)
    await waitFor(() => expect(createSignedUrls).toHaveBeenCalledTimes(2))
    await waitFor(() => expect(document.querySelector('img')).toBeNull())
  })

  it('aMountedCoverIsSignedAgainBeforeItsLinkExpires', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    const { storage, createSignedUrls } = fakeStorage()
    render(
      <CoverContext.Provider value={{ owner: 'u-expired-2', storage }}>
        <CoverImage book={book} />
      </CoverContext.Provider>,
    )
    const img = await photo()
    const first = img.getAttribute('src')
    expect(createSignedUrls).toHaveBeenCalledTimes(1)

    // Shortly before the hour is over, the row asks for a new link by itself.
    await act(async () => {
      await vi.advanceTimersByTimeAsync(56 * 60_000)
    })
    await waitFor(() => expect(createSignedUrls).toHaveBeenCalledTimes(2))
    await waitFor(() => expect(document.querySelector('img')?.getAttribute('src')).not.toBe(first))
  })
})
