import { useContext, useEffect, useRef, useState, useSyncExternalStore } from 'react'
import type { Book } from '../sync/model'
import { GeneratedCover } from './generated'
import { CoverContext, KEEP_MS, coverRevision, dropSignedUrl, onCoverRevision, photoPath, signedUrl } from './storage'

type Props = {
  book: Pick<Book, 'id' | 'coverUrl' | 'title' | 'authors'>
  /** A small picture (a table row): the generated cover is just its colour. */
  compact?: boolean
  className?: string
}

/**
 * A book's cover: its photo (through a signed link), a web link directly, otherwise the generated cover. A signed link
 * lives an hour: a row left open asks for a new one before that, and a picture that fails to load gets one new link
 * (once) before the generated cover takes its place.
 */
export function CoverImage({ book, compact = false, className = '' }: Props) {
  const source = useContext(CoverContext)
  const revision = useSyncExternalStore(onCoverRevision, coverRevision)
  const cover = book.coverUrl
  const isPhoto = cover === 'photo' && source !== null
  const isLink = cover !== null && /^https?:/i.test(cover)
  const path = isPhoto ? photoPath(source.owner, book.id) : null
  const [photo, setPhoto] = useState<{ path: string; revision: number; url: string } | null>(null)
  const [broken, setBroken] = useState<string | null>(null)
  // Bumped to ask for a new link: when the old one is about to expire, or failed to load.
  const [renewal, setRenewal] = useState(0)
  // The link that failed to load, once a new one was asked for; cleared when the next renewal comes by itself.
  const retried = useRef<string | null>(null)

  const storage = source?.storage
  useEffect(() => {
    retried.current = null
  }, [path, revision])
  useEffect(() => {
    if (path === null || !storage) return
    let live = true
    let timer: ReturnType<typeof setTimeout> | undefined
    signedUrl(storage, path).then(
      (url) => {
        if (!live) return
        // The new link is the one that just failed: loading it again can't help, so the generated cover shows.
        if (retried.current !== null && url === retried.current) {
          setBroken(url)
          return
        }
        setPhoto({ path, revision, url })
        // Shortly before the link expires, sign a new one (the shared cache has let go of the old one by then).
        timer = setTimeout(() => {
          retried.current = null
          setRenewal((n) => n + 1)
        }, KEEP_MS)
      },
      () => {
        if (live) setBroken(`${path}#${revision}`)
      },
    )
    return () => {
      live = false
      if (timer) clearTimeout(timer)
    }
  }, [storage, path, revision, renewal])

  const src = isLink ? cover : photo && photo.path === path && photo.revision === revision ? photo.url : null
  if (src === null || broken === src || (path !== null && broken === `${path}#${revision}`)) {
    return <GeneratedCover title={book.title} authors={book.authors} compact={compact} className={className} />
  }
  return (
    <img
      className={`cover-img ${className}`.trim()}
      src={src}
      alt={compact ? '' : `Cover of ${book.title}`}
      loading="lazy"
      onError={() => {
        if (path !== null && storage && retried.current === null) {
          // Most likely the link expired: sign a new one, once.
          retried.current = src
          dropSignedUrl(storage, path)
          setRenewal((n) => n + 1)
          return
        }
        setBroken(src)
      }}
    />
  )
}
