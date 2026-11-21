import { useEffect, useId, useRef, useState, type ReactNode } from 'react'
import { Link, useLocation } from '@tanstack/react-router'
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
  news: svg(
    <>
      <path d="M5 5h11v14H7a2 2 0 0 1-2-2z" />
      <path d="M16 9h3v8a2 2 0 0 1-2 2" />
      <path d="M8 9h5M8 12.5h5M8 16h3" />
    </>,
  ),
  year: svg(
    <>
      <rect x="4" y="5.5" width="16" height="14" rx="2" />
      <path d="M4 10h16M8.5 3.5v4M15.5 3.5v4" />
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
const MORE_PATHS = ['/import', '/tidy']

/**
 * The phone's main navigation, fixed at the bottom where a thumb reaches (the installed app has no browser Back button):
 * Library, Add, News, Year, and More for Import, Tidy up, the theme and signing out.
 */
export function BottomBar({ onSignOut }: { onSignOut: () => void }) {
  const [open, setOpen] = useState(false)
  const pathname = useLocation({ select: (l) => l.pathname })
  const sheetId = useId()
  const moreRef = useRef<HTMLButtonElement>(null)
  const holdsCurrentPage = MORE_PATHS.some((p) => pathname === p || pathname.startsWith(`${p}/`))

  // A page was chosen (here or anywhere): the sheet is done.
  useEffect(() => setOpen(false), [pathname])

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
        <div className="more-backdrop" onClick={() => setOpen(false)}>
          <div id={sheetId} className="more-sheet" role="dialog" aria-label="More" onClick={(e) => e.stopPropagation()}>
            <Link to="/import" onClick={() => setOpen(false)}>
              Import
            </Link>
            <Link to="/tidy" onClick={() => setOpen(false)}>
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
        <Link to="/news">
          {icons.news}
          <span>News</span>
        </Link>
        <Link to="/year">
          {icons.year}
          <span>Year</span>
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
