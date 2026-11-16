import type { LibraryStore } from '../sync/store'
import { BUCKET, forgetSignedUrl, photoPath, type CoverStorage } from './storage'

export const MAX_SIDE = 1000
export const MAX_BYTES = 2_000_000

/** A picture opened for re-drawing: its size and a way to draw it at another size as a JPEG. */
export type DecodedImage = {
  width: number
  height: number
  encode(width: number, height: number, quality: number): Promise<Blob>
  close?(): void
}
export type ImageDecoder = (file: Blob) => Promise<DecodedImage>

/** The browser's canvas, over a white page (a PNG's see-through parts would turn black in a JPEG). */
export const canvasDecoder: ImageDecoder = async (file) => {
  const bitmap = await createImageBitmap(file)
  return {
    width: bitmap.width,
    height: bitmap.height,
    close: () => bitmap.close(),
    encode(width, height, quality) {
      const canvas = document.createElement('canvas')
      canvas.width = width
      canvas.height = height
      const ctx = canvas.getContext('2d')
      if (!ctx) return Promise.reject(new Error('This browser cannot resize pictures.'))
      ctx.fillStyle = '#fff'
      ctx.fillRect(0, 0, width, height)
      ctx.drawImage(bitmap, 0, 0, width, height)
      return new Promise((resolve, reject) =>
        canvas.toBlob((b) => (b ? resolve(b) : reject(new Error('Could not make a JPEG.'))), 'image/jpeg', quality),
      )
    },
  }
}

const QUALITIES = [0.9, 0.8, 0.7, 0.6, 0.5, 0.4, 0.3]

/**
 * The picture as a JPEG whose long side is at most [maxSide] and whose size is at most [maxBytes]: drawn at the
 * wanted size, with the quality lowered until it fits (and, for an image that never does, the size too).
 */
export async function shrinkToJpeg(
  file: Blob,
  maxSide = MAX_SIDE,
  maxBytes = MAX_BYTES,
  decode: ImageDecoder = canvasDecoder,
): Promise<Blob> {
  const image = await decode(file)
  try {
    if (!(image.width > 0 && image.height > 0)) throw new Error('That picture is empty.')
    let scale = Math.min(1, maxSide / Math.max(image.width, image.height))
    for (;;) {
      const width = Math.max(1, Math.round(image.width * scale))
      const height = Math.max(1, Math.round(image.height * scale))
      for (const quality of QUALITIES) {
        const out = await image.encode(width, height, quality)
        if (out.size <= maxBytes) return out
      }
      if (width === 1 && height === 1) throw new Error('That picture is too large to use as a cover.')
      scale *= 0.8
    }
  } finally {
    image.close?.()
  }
}

/**
 * Puts [blob] (already a JPEG within the limits) at `covers/<owner>/<book>.jpg`, then marks the book's cover as a
 * photo. The cover's time is stamped anew on every upload: phones fetch a new photo when that time moves.
 */
export async function uploadCover(
  owner: string,
  bookId: string,
  blob: Blob,
  store: Pick<LibraryStore, 'edit'>,
  storage: CoverStorage,
): Promise<void> {
  const path = photoPath(owner, bookId)
  const { error } = await storage.from(BUCKET).upload(path, blob, { upsert: true, contentType: 'image/jpeg' })
  if (error) throw new Error(error.message)
  forgetSignedUrl(storage, path)
  store.edit('books', bookId, { cover_url: 'photo' }, { restamp: ['cover_url'] })
}
