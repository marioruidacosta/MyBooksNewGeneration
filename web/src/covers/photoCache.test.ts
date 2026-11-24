import 'fake-indexeddb/auto'
import { beforeAll, describe, expect, it, vi } from 'vitest'
import { blobBytes } from '../io/blobBytes'
import { dropPhoto, keepPhoto, keptNow, replacePhoto, savedPhoto } from './photoCache'

const DAY = 24 * 60 * 60 * 1000
let n = 0
const owner = () => `owner-${String(++n)}`
const jpeg = (...bytes: number[]) => new Blob([new Uint8Array(bytes)], { type: 'image/jpeg' })

beforeAll(() => {
  URL.createObjectURL = vi.fn(() => `blob:kept-${String(++n)}`)
})

/** A fresh tab: what is in memory is gone, only the browser's database is left. */
async function freshTab() {
  vi.resetModules()
  return import('./photoCache')
}

describe('photos kept on this device', () => {
  it('aKeptPhotoIsThereForThatVersion', async () => {
    const me = owner()
    await keepPhoto(me, `${me}/b1.jpg`, 100, jpeg(1, 2, 3), () => 1_000)
    const again = await freshTab()
    const saved = await again.savedPhoto(me, `${me}/b1.jpg`, 100, () => 2_000)
    expect(saved?.url).toMatch(/^blob:/)
    expect(saved?.due).toBe(false)
  })

  it('aNewVersionIsNeverServedTheOldPhoto', async () => {
    const me = owner()
    await keepPhoto(me, `${me}/b1.jpg`, 100, jpeg(1), () => 1_000)
    expect(await savedPhoto(me, `${me}/b1.jpg`, 200)).toBeNull()
    expect(keptNow(`${me}/b1.jpg`, 200)).toBeNull()
    const again = await freshTab()
    expect(await again.savedPhoto(me, `${me}/b1.jpg`, 200)).toBeNull()
  })

  it('anAccountNeverReadsAnothersPhoto', async () => {
    const me = owner()
    const other = owner()
    await keepPhoto(me, `${me}/b1.jpg`, 100, jpeg(1))
    expect(await savedPhoto(other, `${me}/b1.jpg`, 100)).toBeNull()
    await keepPhoto(other, `${me}/b2.jpg`, 100, jpeg(2))
    expect(await savedPhoto(me, `${me}/b2.jpg`, 100)).toBeNull()
  })

  it('aPhotoIsDueACheckAfterADay', async () => {
    const me = owner()
    await keepPhoto(me, `${me}/b1.jpg`, 100, jpeg(1), () => 1_000)
    expect((await savedPhoto(me, `${me}/b1.jpg`, 100, () => 1_000 + DAY - 1))?.due).toBe(false)
    expect((await savedPhoto(me, `${me}/b1.jpg`, 100, () => 1_000 + DAY + 1))?.due).toBe(true)
  })

  it('theSamePictureIsOnlyMarkedChecked', async () => {
    const me = owner()
    await keepPhoto(me, `${me}/b1.jpg`, 100, jpeg(1, 2), () => 1_000)
    expect(await replacePhoto(me, `${me}/b1.jpg`, 100, jpeg(1, 2), () => 1_000 + 2 * DAY)).toBeNull()
    expect((await savedPhoto(me, `${me}/b1.jpg`, 100, () => 1_000 + 2 * DAY + 1))?.due).toBe(false)
    const again = await freshTab()
    expect((await again.savedPhoto(me, `${me}/b1.jpg`, 100, () => 1_000 + 2 * DAY + 1))?.due).toBe(false)
  })

  it('aDifferentPictureReplacesTheKeptOne', async () => {
    const me = owner()
    await keepPhoto(me, `${me}/b1.jpg`, 100, jpeg(1, 2), () => 1_000)
    const before = keptNow(`${me}/b1.jpg`, 100)?.url
    const url = await replacePhoto(me, `${me}/b1.jpg`, 100, jpeg(9, 9), () => 2_000)
    expect(url).toMatch(/^blob:/)
    expect(url).not.toBe(before)
    expect(keptNow(`${me}/b1.jpg`, 100)?.url).toBe(url)
    const created = vi.mocked(URL.createObjectURL).mock.calls.at(-1)?.[0] as Blob
    expect([...(await blobBytes(created))]).toEqual([9, 9])
  })

  it('droppingAPhotoForgetsIt', async () => {
    const me = owner()
    await keepPhoto(me, `${me}/b1.jpg`, 100, jpeg(1))
    await dropPhoto(`${me}/b1.jpg`)
    expect(keptNow(`${me}/b1.jpg`, 100)).toBeNull()
    const again = await freshTab()
    expect(await again.savedPhoto(me, `${me}/b1.jpg`, 100)).toBeNull()
  })

  it('withoutBrowserStorageNothingBreaks', async () => {
    const real = globalThis.indexedDB
    vi.stubGlobal('indexedDB', undefined)
    try {
      const again = await freshTab()
      const me = owner()
      await expect(again.keepPhoto(me, `${me}/b1.jpg`, 100, jpeg(1))).resolves.toBeUndefined()
      await expect(again.dropPhoto(`${me}/b2.jpg`)).resolves.toBeUndefined()
      expect(await again.savedPhoto(me, `${me}/b2.jpg`, 100)).toBeNull()
    } finally {
      vi.stubGlobal('indexedDB', real)
    }
  })
})
