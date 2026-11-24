import 'fake-indexeddb/auto'
import { fireEvent, render, waitFor } from '@testing-library/react'
import type { ReactNode } from 'react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { LibraryStore } from '../sync/store'
import { StoreContext } from '../sync/StoreContext'
import { CoverImage } from './CoverImage'
import { keepPhoto, keptNow, savedPhoto } from './photoCache'
import { CoverContext, type CoverStorage } from './storage'

const DAY = 24 * 60 * 60 * 1000
let n = 0
const jpeg = (...bytes: number[]) => new Blob([new Uint8Array(bytes)], { type: 'image/jpeg' })

function fakeStorage() {
  const createSignedUrls = vi.fn(async (paths: string[]) => ({
    data: paths.map((path) => ({ path, signedUrl: `https://signed.example/${path}?n=${String(++n)}`, error: null })),
    error: null,
  }))
  const storage: CoverStorage = { from: () => ({ upload: vi.fn(), createSignedUrls }) }
  return { storage, createSignedUrls }
}

const fetchMock = vi.fn(async () => ({ ok: true, blob: async () => jpeg(7, 7) }))
const book = { id: 'b1', coverUrl: 'photo', title: 'Blindness', authors: 'Saramago' }
const newOwner = () => `kept-${String(++n)}`

function shown(owner: string, storage: CoverStorage, stamp: number, child: ReactNode = <CoverImage book={book} />) {
  const store = { bookTimes: () => ({ cover_url: stamp }) } as unknown as LibraryStore
  return (
    <StoreContext.Provider value={store}>
      <CoverContext.Provider value={{ owner, storage }}>{child}</CoverContext.Provider>
    </StoreContext.Provider>
  )
}

const img = () => waitFor(() => {
  const el = document.querySelector('img')
  expect(el).not.toBeNull()
  return el as HTMLImageElement
})

beforeEach(() => {
  fetchMock.mockClear()
  vi.stubGlobal('fetch', fetchMock)
  URL.createObjectURL = vi.fn(() => `blob:kept-${String(++n)}`)
})
afterEach(() => vi.unstubAllGlobals())

describe('covers kept on this device', () => {
  it('aKeptPhotoShowsWithoutAskingTheNetwork', async () => {
    const owner = newOwner()
    await keepPhoto(owner, `${owner}/b1.jpg`, 100, jpeg(1))
    const { storage, createSignedUrls } = fakeStorage()
    render(shown(owner, storage, 100))
    expect((await img()).getAttribute('src')).toMatch(/^blob:/)
    expect(createSignedUrls).not.toHaveBeenCalled()
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('aLoadedPhotoIsKeptForNextTime', async () => {
    const owner = newOwner()
    const { storage } = fakeStorage()
    render(shown(owner, storage, 100))
    const el = await img()
    expect(el.getAttribute('src')).toMatch(/^https:\/\/signed/)
    fireEvent.load(el)
    await waitFor(() => expect(keptNow(`${owner}/b1.jpg`, 100)).not.toBeNull())
    expect(await savedPhoto(owner, `${owner}/b1.jpg`, 100)).not.toBeNull()
    expect(fetchMock).toHaveBeenCalledTimes(1)
    // The picture on screen stays as it is, with no flicker.
    expect(document.querySelector('img')?.getAttribute('src')).toMatch(/^https:\/\/signed/)
  })

  it('aChangedPhotoIsFetchedAgain', async () => {
    const owner = newOwner()
    await keepPhoto(owner, `${owner}/b1.jpg`, 100, jpeg(1))
    const { storage, createSignedUrls } = fakeStorage()
    render(shown(owner, storage, 200))
    expect((await img()).getAttribute('src')).toMatch(/^https:\/\/signed/)
    expect(createSignedUrls).toHaveBeenCalledTimes(1)
  })

  it('aKeptPhotoThatCannotShowFallsBackToTheNetwork', async () => {
    const owner = newOwner()
    await keepPhoto(owner, `${owner}/b1.jpg`, 100, jpeg(1))
    const { storage, createSignedUrls } = fakeStorage()
    render(shown(owner, storage, 100))
    fireEvent.error(await img())
    await waitFor(() => expect(createSignedUrls).toHaveBeenCalledTimes(1))
    await waitFor(() => expect(document.querySelector('img')?.getAttribute('src')).toMatch(/^https:\/\/signed/))
    expect(keptNow(`${owner}/b1.jpg`, 100)).toBeNull()
  })

  it('anOldKeptPhotoIsCheckedAndReplaced', async () => {
    const owner = newOwner()
    await keepPhoto(owner, `${owner}/b1.jpg`, 100, jpeg(1), () => Date.now() - 2 * DAY)
    const before = keptNow(`${owner}/b1.jpg`, 100)?.url
    const { storage } = fakeStorage()
    render(shown(owner, storage, 100))
    expect((await img()).getAttribute('src')).toBe(before)
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(1))
    await waitFor(() => {
      const src = document.querySelector('img')?.getAttribute('src')
      expect(src).toMatch(/^blob:/)
      expect(src).not.toBe(before)
    })
  })

  it('aFreshKeptPhotoIsNotCheckedAgain', async () => {
    const owner = newOwner()
    await keepPhoto(owner, `${owner}/b1.jpg`, 100, jpeg(1))
    const { storage, createSignedUrls } = fakeStorage()
    render(shown(owner, storage, 100))
    await img()
    await new Promise((r) => setTimeout(r, 20))
    expect(createSignedUrls).not.toHaveBeenCalled()
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('aPublicCoverIsNotKeptHere', async () => {
    const owner = newOwner()
    const { storage, createSignedUrls } = fakeStorage()
    render(shown(owner, storage, 100, <CoverImage book={{ ...book, coverUrl: 'https://covers.openlibrary.org/b/id/1-M.jpg' }} />))
    fireEvent.load(await img())
    await new Promise((r) => setTimeout(r, 20))
    expect(createSignedUrls).not.toHaveBeenCalled()
    expect(fetchMock).not.toHaveBeenCalled()
  })
})
