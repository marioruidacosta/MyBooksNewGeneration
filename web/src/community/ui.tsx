import { useEffect, useId, useRef, useState, type ReactNode } from 'react'
import { GeneratedCover } from '../covers/generated'
import { communityMessage } from './api'
import { useCommunity } from './useCommunity'
import type { Person } from './model'

/** Someone's picture, or their initial on a coloured disc. */
export function Avatar({ person, size = 40 }: { person: Pick<Person, 'displayName' | 'avatarUrl'>; size?: number }) {
  const [failed, setFailed] = useState(false)
  const style = { width: size, height: size, fontSize: size * 0.42 }
  if (person.avatarUrl && !failed) {
    return (
      <img
        className="avatar"
        style={style}
        src={person.avatarUrl}
        alt=""
        loading="lazy"
        referrerPolicy="no-referrer"
        onError={() => setFailed(true)}
      />
    )
  }
  return (
    <span className="avatar avatar--letter" style={style} aria-hidden="true">
      {person.displayName.trim().charAt(0).toUpperCase() || '?'}
    </span>
  )
}

/** Someone's name, as a link to their page. */
export function PersonLink({ person, className = '' }: { person: Person; className?: string }) {
  const { nav } = useCommunity()
  return (
    <a
      className={`person-link ${className}`.trim()}
      href={nav.personHref(person.id)}
      onClick={(e) => {
        if (e.button !== 0 || e.metaKey || e.ctrlKey || e.shiftKey || e.altKey) return
        e.preventDefault()
        nav.openPerson(person.id)
      }}
    >
      {person.displayName}
    </a>
  )
}

/** A row for a person: picture, name, @username, and whatever goes on the right. */
export function PersonRow({ person, detail, children }: { person: Person; detail?: ReactNode; children?: ReactNode }) {
  return (
    <li className="person-row">
      <Avatar person={person} />
      <span className="person-row__text">
        <PersonLink person={person} />
        <span className="person-row__detail">{detail ?? `@${person.username}`}</span>
      </span>
      {children && <span className="person-row__actions">{children}</span>}
    </li>
  )
}

/** A book's cover from the community (another reader's link), or one drawn for it. */
export function CommunityCover({
  title, authors, url, className = '',
}: { title: string; authors: string; url: string | null; className?: string }) {
  const [failed, setFailed] = useState(false)
  if (url && !failed) {
    return (
      <img
        className={`community-cover ${className}`.trim()}
        src={url}
        alt={`Cover of ${title}`}
        loading="lazy"
        referrerPolicy="no-referrer"
        onError={() => setFailed(true)}
      />
    )
  }
  return <GeneratedCover title={title} authors={authors} className={`community-cover ${className}`.trim()} />
}

const FOCUSABLE = 'a[href], button:not(:disabled), input:not(:disabled), select:not(:disabled), textarea:not(:disabled)'

/** A small dialog: takes focus, keeps Tab inside, closes on Escape or a tap outside, and gives focus back. */
export function Dialog({ title, onClose, children }: { title: string; onClose: () => void; children: ReactNode }) {
  const panel = useRef<HTMLDivElement>(null)
  const titleId = useId()
  const close = useRef(onClose)
  close.current = onClose

  useEffect(() => {
    const opener = document.activeElement instanceof HTMLElement ? document.activeElement : null
    panel.current?.querySelector<HTMLElement>(FOCUSABLE)?.focus()
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        e.stopPropagation()
        close.current()
        return
      }
      if (e.key !== 'Tab' || !panel.current) return
      const items = [...panel.current.querySelectorAll<HTMLElement>(FOCUSABLE)]
      if (items.length === 0) return
      const first = items[0]
      const last = items[items.length - 1]
      if (e.shiftKey && document.activeElement === first) {
        e.preventDefault()
        last.focus()
      } else if (!e.shiftKey && document.activeElement === last) {
        e.preventDefault()
        first.focus()
      }
    }
    document.addEventListener('keydown', onKey)
    return () => {
      document.removeEventListener('keydown', onKey)
      if (opener?.isConnected) opener.focus()
    }
  }, [])

  return (
    <div className="dialog-backdrop" onClick={onClose}>
      <div
        ref={panel}
        className="dialog"
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        onClick={(e) => e.stopPropagation()}
      >
        <h2 id={titleId}>{title}</h2>
        {children}
      </div>
    </div>
  )
}

/** A question with a confirm button; the action's failure shows here and leaves it open. */
export function ConfirmDialog({
  title, body, confirm, danger = false, onConfirm, onClose,
}: {
  title: string
  body: ReactNode
  confirm: string
  danger?: boolean
  onConfirm: () => Promise<void>
  onClose: () => void
}) {
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const run = async () => {
    setBusy(true)
    setError(null)
    try {
      await onConfirm()
      onClose()
    } catch (e) {
      setError(communityMessage(e))
      setBusy(false)
    }
  }
  return (
    <Dialog title={title} onClose={onClose}>
      <p>{body}</p>
      {error && <p className="error" role="alert">{error}</p>}
      <div className="dialog__actions">
        <button type="button" className="quiet" onClick={onClose}>Cancel</button>
        <button type="button" className={danger ? 'danger' : 'primary'} disabled={busy} onClick={() => void run()}>
          {confirm}
        </button>
      </div>
    </Dialog>
  )
}

/** A failure with a way to try again. */
export function LoadError({ message, onRetry }: { message: string; onRetry: () => void }) {
  return (
    <div className="community-error">
      <p className="error" role="alert">{message}</p>
      <button type="button" onClick={onRetry}>Try again</button>
    </div>
  )
}
