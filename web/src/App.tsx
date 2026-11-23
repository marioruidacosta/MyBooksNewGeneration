import { Suspense, lazy, useCallback, useContext, useEffect, useMemo, useState, useSyncExternalStore, type ReactNode } from 'react'
import {
  Link,
  Outlet,
  RouterProvider,
  createRootRouteWithContext,
  createRoute,
  createRouter,
  redirect,
  useNavigate,
} from '@tanstack/react-router'
import { SignIn } from './auth/SignIn'
import { signOut } from './auth/signOut'
import { useSession } from './auth/useSession'
import { AddPage } from './add/AddPage'
import { BookPage } from './book/BookPage'
import { CoverContext } from './covers/storage'
import { JumpBox } from './jump/JumpBox'
import { LibraryPage } from './library/LibraryPage'
import { PageBoundary } from './PageBoundary'
import { UpdateNotice } from './pwa/UpdateNotice'
import { updates } from './pwa/updates'
import { ThemeSwitch } from './ThemeSwitch'
import { Publisher } from './share/publisher'
import { SharingContext } from './share/SharingContext'
import { supabase } from './supabase'
import { createSupabaseSyncApi, type SyncApi } from './sync/api'
import { SaveBadge } from './sync/SaveBadge'
import { LibraryStore } from './sync/store'
import { StoreContext, useOptionalStore, useStore } from './sync/StoreContext'
import type { LibraryStore as Store } from './sync/store'
import { useLibraryStore, useWarnBeforeClosing } from './sync/useLibraryStore'
import { ToastProvider } from './ui/Toast'
import { usePhone } from './ui/usePhone'
import { useCommitOnHide } from './ui/useCommitOnHide'
import { BottomBar } from './shell/BottomBar'
import { createCommunityApi } from './community/api'
import { CommunityProvider } from './community/CommunityProvider'
import { shelfIds } from './physical/model'
import { bookcasesOf } from './physical/places'
import { FollowSuggestions, SuggestionBadge } from './community/SuggestionBadge'
import type { CommunityNav } from './community/useCommunity'
import { InstallHint } from './pwa/InstallHint'

// The import and tidy pages (with the ZIP reader and the import code) load when first opened.
const ImportPage = lazy(() => import('./io/ImportPage').then((m) => ({ default: m.ImportPage })))
const TidyPage = lazy(() => import('./tidy/TidyPage').then((m) => ({ default: m.TidyPage })))
const YearPage = lazy(() => import('./year/YearPage').then((m) => ({ default: m.YearPage })))
const NewsPage = lazy(() => import('./news/NewsPage').then((m) => ({ default: m.NewsPage })))
const ChallengesPage = lazy(() => import('./year/ChallengesPage').then((m) => ({ default: m.ChallengesPage })))
const CommunityPage = lazy(() => import('./community/CommunityPage').then((m) => ({ default: m.CommunityPage })))
const PeoplePage = lazy(() => import('./community/PeoplePage').then((m) => ({ default: m.PeoplePage })))
const PersonPage = lazy(() => import('./community/PersonPage').then((m) => ({ default: m.PersonPage })))
const AccountPage = lazy(() => import('./community/AccountPage').then((m) => ({ default: m.AccountPage })))
const PhysicalLibraryPage = lazy(() => import('./physical/PhysicalLibraryPage').then((m) => ({ default: m.PhysicalLibraryPage })))
const BookcaseEditPage = lazy(() => import('./physical/BookcaseEditPage').then((m) => ({ default: m.BookcaseEditPage })))
const BookcasePage = lazy(() => import('./physical/BookcasePage').then((m) => ({ default: m.BookcasePage })))
const ShelfPage = lazy(() => import('./physical/ShelfPage').then((m) => ({ default: m.ShelfPage })))
const WithoutPlacePage = lazy(() => import('./physical/WithoutPlacePage').then((m) => ({ default: m.WithoutPlacePage })))

const loadingPage = <p className="loading">Loading…</p>

interface RouterContext {
  signedIn: boolean
}

/** Ctrl K from any signed-in page: a book to open, or an ISBN to add. */
function Jump({ store, compact }: { store: Store; compact: boolean }) {
  const books = useSyncExternalStore(store.subscribe, () => store.books())
  const navigate = useNavigate()
  return (
    <JumpBox
      books={books}
      compact={compact}
      onOpen={(id) => void navigate({ to: '/book/$id', params: { id } })}
      onAddIsbn={(isbn) => void navigate({ to: '/add', search: { isbn } })}
    />
  )
}

/** The community for the signed-in account's pages; its profile is loaded when a page first needs it. */
function WithCommunity({ children }: { children: ReactNode }) {
  const owner = useContext(CoverContext)?.owner
  const navigate = useNavigate()
  const api = useMemo(() => (owner ? createCommunityApi(supabase, owner) : null), [owner])
  const nav = useMemo<CommunityNav>(() => ({
    personHref: (id) => `${import.meta.env.BASE_URL}community/person/${id}`,
    openPerson: (id) => void navigate({ to: '/community/person/$id', params: { id } }),
    openBook: (id) => void navigate({ to: '/book/$id', params: { id } }),
    openPeople: () => void navigate({ to: '/community/people' }),
    openAccount: () => void navigate({ to: '/community/account' }),
    openFeed: () => void navigate({ to: '/community' }),
  }), [navigate])
  return api ? <CommunityProvider key={owner} api={api} nav={nav}>{children}</CommunityProvider> : <>{children}</>
}

function Shell() {
  const store = useOptionalStore()
  const phone = usePhone()
  useCommitOnHide()
  const publisher = useContext(SharingContext)
  return (
    <WithCommunity>
    <FollowSuggestions />
    <ToastProvider pinned={<UpdateNotice watcher={updates} store={store} publisher={publisher} />}>
      {store && (
        <header className="app-bar">
          <span className="wordmark">MyBooks</span>
          {!phone && (
            <nav aria-label="Pages">
              <Link to="/" activeOptions={{ exact: true }}>
                Library
              </Link>
              <Link to="/add">Add a book</Link>
              <Link to="/community">Community<SuggestionBadge /></Link>
              <Link to="/challenges">Challenges</Link>
              <Link to="/physical">Physical library</Link>
              <Link to="/import">Import</Link>
              <Link to="/tidy">Tidy up</Link>
              <Link to="/year">Year in books</Link>
              <Link to="/news">Book news</Link>
            </nav>
          )}
          <Jump store={store} compact={phone} />
          <SaveBadge store={store} />
          {!phone && <ThemeSwitch />}
          {!phone && (
            <button type="button" className="quiet" onClick={() => void signOut()}>
              Sign out
            </button>
          )}
        </header>
      )}
      {store && <InstallHint />}
      <Outlet />
      {store && phone && <BottomBar onSignOut={() => void signOut()} />}
    </ToastProvider>
    </WithCommunity>
  )
}

function LibraryRoute() {
  const store = useStore()
  const navigate = useNavigate()
  const onAdd = useCallback(() => void navigate({ to: '/add' }), [navigate])
  const onOpen = useCallback((id: string) => void navigate({ to: '/book/$id', params: { id } }), [navigate])
  return <LibraryPage store={store} onAdd={onAdd} onOpen={onOpen} />
}

function AddRoute() {
  const store = useStore()
  const navigate = useNavigate()
  const { isbn } = addRoute.useSearch()
  return <AddPage key={isbn ?? ''} store={store} initialIsbn={isbn} onOpen={(id) => void navigate({ to: '/book/$id', params: { id } })} />
}

function ImportRoute() {
  const store = useStore()
  const covers = useContext(CoverContext)
  return (
    <PageBoundary>
      <Suspense fallback={loadingPage}>
        <ImportPage store={store} owner={covers?.owner} storage={covers?.storage} />
      </Suspense>
    </PageBoundary>
  )
}

function TidyRoute() {
  const store = useStore()
  const covers = useContext(CoverContext)
  if (!covers) throw new Error('TidyRoute: no signed-in account')
  return (
    <PageBoundary>
      <Suspense fallback={loadingPage}>
        <TidyPage store={store} owner={covers.owner} storage={supabase.storage} />
      </Suspense>
    </PageBoundary>
  )
}

function YearRoute() {
  const store = useStore()
  const navigate = useNavigate()
  const { year } = yearRoute.useSearch()
  return (
    <PageBoundary>
      <Suspense fallback={loadingPage}>
        <YearPage
          key={year ?? 'now'}
          year={year}
          store={store}
          onOpen={(id) => void navigate({ to: '/book/$id', params: { id } })}
          onLibrary={() => void navigate({ to: '/' })}
        />
      </Suspense>
    </PageBoundary>
  )
}

function ChallengesRoute() {
  const store = useStore()
  const navigate = useNavigate()
  return (
    <PageBoundary>
      <Suspense fallback={loadingPage}>
        <ChallengesPage
          store={store}
          onOpen={(id) => void navigate({ to: '/book/$id', params: { id } })}
          onOpenYear={(year) => void navigate({ to: '/year', search: { year } })}
        />
      </Suspense>
    </PageBoundary>
  )
}

/** A community page, loaded when first opened. */
function CommunityRoutePage({ children }: { children: ReactNode }) {
  return (
    <PageBoundary>
      <Suspense fallback={loadingPage}>{children}</Suspense>
    </PageBoundary>
  )
}

function PersonRoute() {
  const { id } = personRoute.useParams()
  return <CommunityRoutePage><PersonPage id={id} /></CommunityRoutePage>
}

function NewsRoute() {
  return (
    <PageBoundary>
      <Suspense fallback={loadingPage}>
        <NewsPage />
      </Suspense>
    </PageBoundary>
  )
}

function BookRoute() {
  const store = useStore()
  const { id } = bookRoute.useParams()
  const owner = useContext(CoverContext)?.owner
  if (!owner) throw new Error('BookRoute: no signed-in account')
  const navigate = useNavigate()
  const { from } = bookRoute.useSearch()
  const shelfId = from?.startsWith('shelf:') ? from.slice('shelf:'.length) : null
  const back = shelfId !== null
    ? { label: 'Back to the shelf', go: () => void navigate({ to: '/physical/shelf/$shelfId', params: { shelfId } }) }
    : from === 'unplaced'
      ? { label: 'Back to Without a place', go: () => void navigate({ to: '/physical/unplaced' }) }
      : from === 'physical'
        ? { label: 'Back to Physical library', go: () => void navigate({ to: '/physical' }) }
        : { label: 'Back to the library', go: () => void navigate({ to: '/' }) }
  return (
    <BookPage
      store={store}
      owner={owner}
      id={id}
      onBack={back.go}
      backLabel={back.label}
      onOpenShelf={(shelfId) => void navigate({ to: '/physical/shelf/$shelfId', params: { shelfId } })}
      onAddBookcase={() => void navigate({ to: '/physical/new' })}
    />
  )
}

function PhysicalRoute() {
  const store = useStore()
  const navigate = useNavigate()
  return (
    <PageBoundary>
      <Suspense fallback={loadingPage}>
        <PhysicalLibraryPage
          store={store}
          onOpenBook={(id) => void navigate({ to: '/book/$id', params: { id }, search: { from: 'physical' } })}
          onOpenBookcase={(id) => void navigate({ to: '/physical/$id', params: { id } })}
          onAdd={() => void navigate({ to: '/physical/new' })}
          onOpenUnplaced={() => void navigate({ to: '/physical/unplaced' })}
        />
      </Suspense>
    </PageBoundary>
  )
}

function BookcaseEditRoute({ id }: { id: string | null }) {
  const store = useStore()
  const navigate = useNavigate()
  return (
    <PageBoundary>
      <Suspense fallback={loadingPage}>
        <BookcaseEditPage
          key={id ?? 'new'}
          store={store}
          id={id}
          onDone={() => void (id === null ? navigate({ to: '/physical' }) : navigate({ to: '/physical/$id', params: { id }, replace: true }))}
        />
      </Suspense>
    </PageBoundary>
  )
}

function BookcaseRoute() {
  const store = useStore()
  const navigate = useNavigate()
  const { id } = bookcaseRoute.useParams()
  const back = useCallback(() => void navigate({ to: '/physical' }), [navigate])
  return (
    <PageBoundary>
      <Suspense fallback={loadingPage}>
        <BookcasePage
          store={store}
          id={id}
          onBack={back}
          onEdit={() => void navigate({ to: '/physical/$id/edit', params: { id } })}
          onOpenShelf={(shelfId) => void navigate({ to: '/physical/shelf/$shelfId', params: { shelfId } })}
        />
      </Suspense>
    </PageBoundary>
  )
}

function ShelfRoute() {
  const store = useStore()
  const navigate = useNavigate()
  const { shelfId } = shelfRoute.useParams()
  const back = useCallback(() => {
    const owner = bookcasesOf(store).find((b) => shelfIds(b).includes(shelfId))
    void (owner ? navigate({ to: '/physical/$id', params: { id: owner.id } }) : navigate({ to: '/physical' }))
  }, [navigate, store, shelfId])
  return (
    <PageBoundary>
      <Suspense fallback={loadingPage}>
        <ShelfPage
          key={shelfId}
          store={store}
          shelfId={shelfId}
          onBack={back}
          onOpenBook={(id) => void navigate({ to: '/book/$id', params: { id }, search: { from: `shelf:${shelfId}` } })}
        />
      </Suspense>
    </PageBoundary>
  )
}

function WithoutPlaceRoute() {
  const store = useStore()
  const navigate = useNavigate()
  return (
    <PageBoundary>
      <Suspense fallback={loadingPage}>
        <WithoutPlacePage
          store={store}
          onBack={() => void navigate({ to: '/physical' })}
          onOpenBook={(id) => void navigate({ to: '/book/$id', params: { id }, search: { from: 'unplaced' } })}
        />
      </Suspense>
    </PageBoundary>
  )
}

const rootRoute = createRootRouteWithContext<RouterContext>()({ component: Shell })

const requireSession = ({ context }: { context: RouterContext }) => {
  if (!context.signedIn) throw redirect({ to: '/sign-in' })
}

const signInRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/sign-in',
  beforeLoad: ({ context }) => {
    if (context.signedIn) throw redirect({ to: '/' })
  },
  component: SignIn,
})
const libraryRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/',
  beforeLoad: requireSession,
  component: LibraryRoute,
})
/** Where a book's Back returns to. Only these kinds, so a link can't make it go anywhere else. */
function bookSearch(search: Record<string, unknown>): { from?: string } {
  const { from } = search
  return typeof from === 'string' && (from === 'physical' || from === 'unplaced' || /^shelf:[A-Za-z0-9_-]{1,40}$/.test(from)) ? { from } : {}
}

const bookRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/book/$id',
  validateSearch: bookSearch,
  beforeLoad: requireSession,
  component: BookRoute,
})
const addRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/add',
  beforeLoad: requireSession,
  validateSearch: (search: Record<string, unknown>): { isbn?: string } =>
    typeof search.isbn === 'string' && search.isbn !== '' ? { isbn: search.isbn } : {},
  component: AddRoute,
})
const importRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/import',
  beforeLoad: requireSession,
  component: ImportRoute,
})
const tidyRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/tidy',
  beforeLoad: requireSession,
  component: TidyRoute,
})

const yearRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/year',
  beforeLoad: requireSession,
  validateSearch: (search: Record<string, unknown>): { year?: number } => {
    const year = Number(search.year)
    return Number.isInteger(year) && year >= 1900 && year <= 9999 ? { year } : {}
  },
  component: YearRoute,
})

const challengesRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/challenges',
  beforeLoad: requireSession,
  component: ChallengesRoute,
})

const communityRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/community',
  beforeLoad: requireSession,
  component: () => <CommunityRoutePage><CommunityPage /></CommunityRoutePage>,
})

const peopleRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/community/people',
  beforeLoad: requireSession,
  component: () => <CommunityRoutePage><PeoplePage /></CommunityRoutePage>,
})

const personRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/community/person/$id',
  beforeLoad: requireSession,
  component: PersonRoute,
})

const accountRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/community/account',
  beforeLoad: requireSession,
  component: () => <CommunityRoutePage><AccountPage /></CommunityRoutePage>,
})

const physicalRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/physical',
  beforeLoad: requireSession,
  component: PhysicalRoute,
})

const newBookcaseRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/physical/new',
  beforeLoad: requireSession,
  component: () => <BookcaseEditRoute id={null} />,
})

const unplacedRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/physical/unplaced',
  beforeLoad: requireSession,
  component: WithoutPlaceRoute,
})

const bookcaseRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/physical/$id',
  beforeLoad: requireSession,
  component: BookcaseRoute,
})

const shelfRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/physical/shelf/$shelfId',
  beforeLoad: requireSession,
  component: ShelfRoute,
})

const editBookcaseRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/physical/$id/edit',
  beforeLoad: requireSession,
  component: function EditBookcase() {
    const { id } = editBookcaseRoute.useParams()
    return <BookcaseEditRoute id={id} />
  },
})

const newsRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/news',
  beforeLoad: requireSession,
  component: NewsRoute,
})

const routeTree = rootRoute.addChildren([signInRoute, libraryRoute, bookRoute, addRoute, importRoute, tidyRoute, yearRoute, challengesRoute, communityRoute, peopleRoute, personRoute, accountRoute, newsRoute, physicalRoute, newBookcaseRoute, unplacedRoute, bookcaseRoute, shelfRoute, editBookcaseRoute])

const router = createRouter({
  routeTree,
  basepath: import.meta.env.BASE_URL.replace(/\/$/, ''),
  context: { signedIn: false },
})

declare module '@tanstack/react-router' {
  interface Register {
    router: typeof router
  }
}

type AppProps = {
  /** The cloud library of an account; the real one unless a test brings its own. */
  createApi?: (owner: string) => SyncApi
}

export function App({ createApi = (owner) => createSupabaseSyncApi(supabase, owner) }: AppProps = {}) {
  const { session, loading } = useSession()
  const userId = session?.user.id ?? null
  // One store for the signed-in account; closed on sign-out and when the account changes.
  const { store, error, notice } = useLibraryStore(userId, (id) => new LibraryStore({ api: createApi(id), owner: id }))
  // Posts this account's changes to the community like the phone does; one per open library.
  const [publisher, setPublisher] = useState<Publisher | null>(null)
  useEffect(() => {
    if (!store) return
    const next = new Publisher({
      store,
      client: supabase,
      owner: userId ?? '',
      readSharing: async () => {
        try {
          const { data, error } = await supabase.from('profiles').select('share_activity').eq('id', userId ?? '').maybeSingle()
          // No profile yet: nothing is shared.
          return error ? 'unknown' : data?.share_activity === true
        } catch {
          return 'unknown'
        }
      },
      currentOwner: async () => {
        try {
          const { data, error } = await supabase.auth.getSession()
          // A failed read (offline, a refresh that didn't work) says nothing about who is signed in.
          return error ? 'unknown' : (data.session?.user.id ?? null)
        } catch {
          return 'unknown'
        }
      },
    })
    let live = true
    void next.start().then(() => {
      if (live) setPublisher(next)
    })
    return () => {
      live = false
      next.stop()
      setPublisher(null)
    }
  }, [store, userId])
  useWarnBeforeClosing(store, publisher)
  // Whose cover photos to show, and where they are kept.
  const covers = useMemo(() => (userId ? { owner: userId, storage: supabase.storage } : null), [userId])

  if (loading) return <p className="loading">Loading…</p>
  if (session && !store) {
    return error ? (
      <main>
        <p className="error" role="alert">
          Your library could not be opened: {error}
        </p>
        <button type="button" onClick={() => void signOut()}>
          Sign out
        </button>
      </main>
    ) : (
      <>
        <p className="loading">Loading…</p>
        {notice && <p role="alert">{notice}</p>}
      </>
    )
  }
  return (
    <StoreContext.Provider value={store}>
      <SharingContext.Provider value={publisher}>
        <CoverContext.Provider value={covers}>
          <RouterProvider key={session ? 'in' : 'out'} router={router} context={{ signedIn: session !== null }} />
        </CoverContext.Provider>
      </SharingContext.Provider>
    </StoreContext.Provider>
  )
}
