import { describe, expect, it, vi } from 'vitest'
import { shrinkToJpeg, uploadCover, type ImageDecoder } from './coverUpload'
import { coverColorIndex } from './coverColor'
import { signedUrl, forgetSignedUrl, type CoverStorage } from './storage'

/** jsdom has no canvas: a stand-in picture whose encoded size follows its pixels and quality like a JPEG's. */
function fakeDecoder(width: number, height: number) {
  const draws: { width: number; height: number; quality: number }[] = []
  const decode: ImageDecoder = async () => ({
    width,
    height,
    encode: async (w, h, quality) => {
      draws.push({ width: w, height: h, quality })
      return new Blob([new Uint8Array(Math.round(w * h * quality * 4))], { type: 'image/jpeg' })
    },
  })
  return { decode, draws }
}

describe('shrinkToJpeg', () => {
  it('shrinkFitsTheBucket', async () => {
    const { decode, draws } = fakeDecoder(4000, 6000)
    const png = new Blob(['png'], { type: 'image/png' })

    const out = await shrinkToJpeg(png, 1000, 2_000_000, decode)

    expect(out.type).toBe('image/jpeg')
    expect(out.size).toBeLessThanOrEqual(2_000_000)
    const last = draws[draws.length - 1]
    expect(Math.max(last.width, last.height)).toBe(1000)
    expect(last).toMatchObject({ width: 667, height: 1000 })
    // The first try (667x1000 at 0.9 = 2.4 MB) was too big, so the quality came down.
    expect(draws.length).toBeGreaterThan(1)
    expect(last.quality).toBeLessThan(0.9)
  })

  it('neverEnlargesASmallPicture', async () => {
    const { decode, draws } = fakeDecoder(300, 200)
    await shrinkToJpeg(new Blob(['x']), 1000, 2_000_000, decode)
    expect(draws).toEqual([{ width: 300, height: 200, quality: 0.9 }])
  })

  it('shrinksTheSizeToo_whenNoQualityFits', async () => {
    const { decode, draws } = fakeDecoder(1000, 1000)
    const out = await shrinkToJpeg(new Blob(['x']), 1000, 300_000, decode)
    expect(out.size).toBeLessThanOrEqual(300_000)
    expect(draws[draws.length - 1].width).toBeLessThan(1000)
  })
})

describe('uploadCover', () => {
  it('upsertsTheFileThenMarksPhotoRestamping', async () => {
    const upload = vi.fn(async () => ({ error: null }))
    const storage: CoverStorage = { from: (b) => (b === 'covers' ? { upload, createSignedUrl: vi.fn() } : (undefined as never)) }
    const edit = vi.fn()
    const blob = new Blob(['j'], { type: 'image/jpeg' })

    await uploadCover('u1', 'b1', blob, { edit }, storage)

    expect(upload).toHaveBeenCalledWith('u1/b1.jpg', blob, { upsert: true, contentType: 'image/jpeg' })
    expect(edit).toHaveBeenCalledWith('books', 'b1', { cover_url: 'photo' }, { restamp: ['cover_url'] })
  })

  it('marksNothingWhenTheUploadFails', async () => {
    const upload = vi.fn(async () => ({ error: { message: 'no' } }))
    const storage: CoverStorage = { from: () => ({ upload, createSignedUrl: vi.fn() }) }
    const edit = vi.fn()
    await expect(uploadCover('u1', 'b1', new Blob(['j']), { edit }, storage)).rejects.toThrow('no')
    expect(edit).not.toHaveBeenCalled()
  })
})

describe('signed links', () => {
  it('areCachedForAboutFiftyFiveMinutesAndForgottenOnUpload', async () => {
    const createSignedUrl = vi.fn(async (path: string) => ({ data: { signedUrl: `s:${path}:${Math.random()}` }, error: null }))
    const storage: CoverStorage = { from: () => ({ upload: vi.fn(), createSignedUrl }) }
    let t = 1_000
    const now = () => t

    const a = await signedUrl(storage, 'u/1.jpg', now)
    expect(await signedUrl(storage, 'u/1.jpg', now)).toBe(a)
    expect(createSignedUrl).toHaveBeenCalledTimes(1)
    expect(createSignedUrl).toHaveBeenCalledWith('u/1.jpg', 3600)

    t += 54 * 60_000
    expect(await signedUrl(storage, 'u/1.jpg', now)).toBe(a)
    t += 2 * 60_000
    expect(await signedUrl(storage, 'u/1.jpg', now)).not.toBe(a)
    expect(createSignedUrl).toHaveBeenCalledTimes(2)

    forgetSignedUrl(storage, 'u/1.jpg')
    await signedUrl(storage, 'u/1.jpg', now)
    expect(createSignedUrl).toHaveBeenCalledTimes(3)
  })
})

describe('coverColorIndex', () => {
  it('isTheAppsHashOfTheFoldedTitle', () => {
    expect(coverColorIndex('a')).toBe(7) // 97 % 10
    expect(coverColorIndex('Ab')).toBe(5) // (97 * 31 + 98) % 10
    expect(coverColorIndex('ÁB')).toBe(coverColorIndex('ab'))
    expect(coverColorIndex('')).toBe(0)
    // Long titles wrap like the app's 31-bit Int (checked against the same loop).
    let h = 0
    const t = 'Ensaio sobre a Cegueira, um romance muito longo'
    for (const c of t.toLowerCase()) h = (Math.imul(h, 31) + c.charCodeAt(0)) & 0x7fffffff
    expect(coverColorIndex(t)).toBe(h % 10)
  })
})
