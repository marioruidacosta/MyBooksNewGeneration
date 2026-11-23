import { useEffect, useId, useRef, useState, type KeyboardEvent as ReactKeyboardEvent, type ReactNode } from 'react'
import { Link, useLocation } from '@tanstack/react-router'
import { SuggestionBadge } from '../community/SuggestionBadge'
import { ThemeSwitch } from '../ThemeSwitch'

const svg = (children: ReactNode) => (
  <svg viewBox="0 0 24 24" width="24" height="24" fill="none" stroke="currentColor" strokeWidth="1.75" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true" focusable="false">
    {children}
  </svg>
)

const icons = {
  library: svg(
    <>
      <path d="M5 4v16" />
      <path d="M10 4v16" />
      <path d="m14.5 5.5 4 14" />
      <path d="M3 20h18" />
    </>,
  ),
  add: svg(
    <>
      <circle cx="12" cy="12" r="8.5" />
      <path d="M12 8v8M8 12h8" />
    </>,
  ),
  community: svg(
    <>
      <circle cx="9" cy="8" r="3.25" />
      <path d="M3 19.5c.6-3.6 3-5.5 6-5.5s5.4 1.9 6 5.5" />
      <path d="M16 5.2a3.25 3.25 0 0 1 0 5.6" />
      <path d="M17.5 14.4c1.9.7 3.1 2.4 3.5 5.1" />
    </>,
  ),
  challenges: svg(
    <>
      <path d="M6 21V4" />
      <path d="M6 4.5c3.5-1.8 6 1.6 12 0v9c-6 1.6-8.5-1.8-12 0" />
    </>,
  ),
  more: svg(
    <>
      <circle cx="5.5" cy="12" r="1.25" />
      <circle cx="12" cy="12" r="1.25" />
      <circle cx="18.5" cy="12" r="1.25" />
    </>,
  ),
}

/** The pages under More: the bar holds the four used most. */
const MORE_PATHS = ['/year', '/physical', '/news', '/import', '/tidy']

/**
 * The phone's main navigation, fixed at the bottom where a thumb reaches (the installed app has no browser Back button):
 * Library, Add, Community, Challenges, and More for the year, news, Import, Tidy up, the theme and signing out.
 */
export function BottomBar({ onSignOut }: { onSignOut: () => void }) {
  const [open, setOpen] = useState(false)
  const pathname = useLocation({ select: (l) => l.pathname })
  const sheetId = useId()
  const moreRef = useRef<HTMLButtonElement>(null)
  const sheetRef = useRef<HTMLDivElement>(null)
  /** A page was chosen from the sheet: once it shows, its heading takes the focus (the link that had it is gone). */
  const headingNext = useRef(false)
  const holdsCurrentPage = MORE_PATHS.some((p) => pathname === p || pathname.startsWith(`${p}/`))

  // A page was chosen (here or anywhere): the sheet is done.
  useEffect(() => {
    setOpen(false)
    if (!headingNext.current) return
    headingNext.current = false
    // The page may still be loading: look for its heading for a moment.
    let tries = 0
    const timer = setInterval(() => {
      const heading = document.querySelector<HTMLElement>('main h1, h1')
      if (heading) {
        if (!heading.hasAttribute('tabindex')) heading.tabIndex = -1
        heading.focus()
      }
      if (heading || ++tries > 40) clearInterval(timer)
    }, 50)
    return () => clearInterval(timer)
  }, [pathname])

  // The sheet takes the focus when it opens.
  useEffect(() => {
    if (open) sheetRef.current?.querySelector<HTMLElement>('a, button, select')?.focus()
  }, [open])

  const closeToMore = () => {
    setOpen(false)
    moreRef.current?.focus()
  }

  /** A link in the sheet was chosen: another page puts focus on its heading; the same page gives it back to More. */
  const choose = (to: string) => {
    if (pathname === to) closeToMore()
    else {
      headingNext.current = true
      setOpen(false)
    }
  }

  /** Tab goes round inside the sheet, which is modal in effect: the page behind is dimmed. */
  const keepTabInside = (e: ReactKeyboardEvent<HTMLDivElement>) => {
    if (e.key !== 'Tab') return
    const items = [...e.currentTarget.querySelectorAll<HTMLElement>('a, button, select')]
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

  useEffect(() => {
    if (!open) return
    const onKey = (e: KeyboardEvent) => {
      if (e.key !== 'Escape') return
      setOpen(false)
      moreRef.current?.focus()
    }
    document.addEventListener('keydown', onKey)
    return () => document.removeEventListener('keydown', onKey)
  }, [open])

  return (
    <>
      {open && (
        <div className="more-backdrop" onClick={closeToMore}>
          <div
            id={sheetId}
            ref={sheetRef}
            className="more-sheet"
            role="dialog"
            aria-label="More"
            onClick={(e) => e.stopPropagation()}
            onKeyDown={keepTabInside}
          >
            <Link to="/year" onClick={() => choose('/year')}>
              Year in books
            </Link>
            <Link to="/physical" onClick={() => choose('/physical')}>
              Physical library
            </Link>
            <Link to="/news" onClick={() => choose('/news')}>
              Book news
            </Link>
            <Link to="/import" onClick={() => choose('/import')}>
              Import
            </Link>
            <Link to="/tidy" onClick={() => choose('/tidy')}>
              Tidy up
            </Link>
            <div className="more-sheet__row">
              <span>Theme</span>
              <ThemeSwitch />
            </div>
            <button type="button" className="quiet" onClick={onSignOut}>
              Sign out
            </button>
          </div>
        </div>
      )}
      <nav className="bottom-bar" aria-label="Main">
        <Link to="/" activeOptions={{ exact: true }}>
          {icons.library}
          <span>Library</span>
        </Link>
        <Link to="/add">
          {icons.add}
          <span>Add</span>
        </Link>
        <Link to="/community">
          {icons.community}
          <span>Community</span>
          <SuggestionBadge />
        </Link>
        <Link to="/challenges">
          {icons.challenges}
          <span>Challenges</span>
        </Link>
        <button
          ref={moreRef}
          type="button"
          className="bottom-bar__more"
          aria-expanded={open}
          aria-controls={open ? sheetId : undefined}
          data-current={holdsCurrentPage ? 'true' : undefined}
          onClick={() => setOpen((o) => !o)}
        >
          {icons.more}
          <span>More</span>
        </button>
      </nav>
    </>
  )
}
