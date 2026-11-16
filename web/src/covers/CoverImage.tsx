import { useContext, useEffect, useState, useSyncExternalStore } from 'react'
import type { Book } from '../sync/model'
import { GeneratedCover } from './generated'
import { CoverContext, coverRevision, onCoverRevision, photoPath, signedUrl } from './storage'

type Props = {
  book: Pick<Book, 'id' | 'coverUrl' | 'title' | 'authors'>
  /** A small picture (a table row): the generated cover is just its colour. */
  compact?: boolean
  className?: string
}

/** A book's cover: its photo (through a signed link), a web link directly, otherwise the generated cover. */
export function CoverImage({ book, compact = false, className = '' }: Props) {
  const source = useContext(CoverContext)
  const revision = useSyncExternalStore(onCoverRevision, coverRevision)
  const cover = book.coverUrl
  const isPhoto = cover === 'photo' && source !== null
  const isLink = cover !== null && /^https?:/i.test(cover)
  const path = isPhoto ? photoPath(source.owner, book.id) : null
  const [photo, setPhoto] = useState<{ path: string; revision: number; url: string } | null>(null)
  const [broken, setBroken] = useState<string | null>(null)

  const storage = source?.storage
  useEffect(() => {
    if (path === null || !storage) return
    let live = true
    signedUrl(storage, path).then(
      (url) => {
        if (live) setPhoto({ path, revision, url })
      },
      () => {
        if (live) setBroken(`${path}#${revision}`)
      },
    )
    return () => {
      live = false
    }
  }, [storage, path, revision])

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
      onError={() => setBroken(src)}
    />
  )
}
