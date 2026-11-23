import { useContext, useEffect, useRef, useState, useSyncExternalStore } from 'react'
import type { Book } from '../sync/model'
import { StoreContext } from '../sync/StoreContext'
import { GeneratedCover } from './generated'
import { dropPhoto, keepPhoto, keptNow, replacePhoto, savedPhoto } from './photoCache'
import { CoverContext, KEEP_MS, coverRevision, dropSignedUrl, onCoverRevision, photoPath, signedUrl } from './storage'

type Props = {
  book: Pick<Book, 'id' | 'coverUrl' | 'title' | 'authors'>
  /** A small picture (a table row): the generated cover is just its colour. */
  compact?: boolean
  className?: string
}

/**
 * A book's cover: its photo, a web link directly, otherwise the generated cover. A photo seen before comes from this
 * device, with no network. Otherwise it loads through a signed link, and is kept once it has loaded. A signed link lives
 * an hour: a row left open asks for a new one before that, and a picture that fails to load gets one new link (once)
 * before the generated cover takes its place.
 */
export function CoverImage({ book, compact = false, className = '' }: Props) {
  const source = useContext(CoverContext)
  const revision = useSyncExternalStore(onCoverRevision, coverRevision)
  const cover = book.coverUrl
  const isPhoto = cover === 'photo' && source !== null
  const isLink = cover !== null && /^https?:/i.test(cover)
  const path = isPhoto ? photoPath(source.owner, book.id) : null
  const owner = source?.owner
  // The cover's time moves whenever the photo changes: a kept picture is only good for the time it was kept at.
  const stamp = useContext(StoreContext)?.bookTimes(book.id).cover_url ?? 0
  const version = path === null ? null : `${path}#${String(stamp)}#${String(revision)}`
  const [kept, setKept] = useState<{ version: string; url: string } | null>(null)
  // The version with nothing kept on this device: only then is a link signed.
  const [notKept, setNotKept] = useState<string | null>(null)
  const keeping = useRef<string | null>(null)
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
    if (path === null || version === null || !owner) return
    let live = true
    void savedPhoto(owner, path, stamp).then((saved) => {
      if (!live) return
      if (!saved) {
        setNotKept(version)
        return
      }
      setKept({ version, url: saved.url })
      if (!saved.due || !storage) return
      // About once a day: is the cloud's picture still this one? A failed check changes nothing.
      void signedUrl(storage, path)
        .then((url) => fetch(url))
        .then((response) => (response.ok ? response.blob() : null))
        .then((blob) => (blob ? replacePhoto(owner, path, stamp, blob) : null))
        .then((url) => {
          if (live && url) setKept({ version, url })
        })
        .catch(() => {})
    })
    return () => {
      live = false
    }
  }, [owner, path, stamp, version, storage])

  useEffect(() => {
    if (path === null || !storage || version === null || notKept !== version) return
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
  }, [storage, path, revision, renewal, version, notKept])

  const keptUrl = path === null || notKept === version
    ? null
    : kept?.version === version ? kept.url : keptNow(path, stamp)?.url ?? null
  const signed = notKept === version && photo && photo.path === path && photo.revision === revision ? photo.url : null
  const src = isLink ? cover : keptUrl ?? signed
  if (src === null || broken === src || (path !== null && broken === `${path}#${revision}`)) {
    return <GeneratedCover title={book.title} authors={book.authors} compact={compact} className={className} />
  }
  return (
    <img
      className={`cover-img ${className}`.trim()}
      src={src}
      alt={compact ? '' : `Cover of ${book.title}`}
      loading="lazy"
      onLoad={() => {
        // Loaded through a link: keep it, once, for next time. What is on screen doesn't change.
        if (path === null || !owner || src !== signed || keeping.current === version) return
        keeping.current = version
        void fetch(src, { cache: 'force-cache' })
          .then((response) => (response.ok ? response.blob() : null))
          .then((blob) => (blob ? keepPhoto(owner, path, stamp, blob) : undefined))
          .catch(() => {})
      }}
      onError={() => {
        if (path !== null && version !== null && src === keptUrl) {
          // The kept copy can't be shown: forget it and load the cloud's.
          void dropPhoto(path)
          setKept(null)
          setNotKept(version)
          return
        }
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
