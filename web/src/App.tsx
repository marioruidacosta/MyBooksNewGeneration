import { Suspense, lazy, useCallback, useContext, useEffect, useMemo, useState, useSyncExternalStore } from 'react'
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
import { BottomBar } from './shell/BottomBar'
import { InstallHint } from './pwa/InstallHint'

// The import and tidy pages (with the ZIP reader and the import code) load when first opened.
const ImportPage = lazy(() => import('./io/ImportPage').then((m) => ({ default: m.ImportPage })))
const TidyPage = lazy(() => import('./tidy/TidyPage').then((m) => ({ default: m.TidyPage })))
const YearPage = lazy(() => import('./year/YearPage').then((m) => ({ default: m.YearPage })))
const NewsPage = lazy(() => import('./news/NewsPage').then((m) => ({ default: m.NewsPage })))

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

function Shell() {
  const store = useOptionalStore()
  const phone = usePhone()
  const publisher = useContext(SharingContext)
  return (
    <ToastProvider>
      {store && (
        <header className="app-bar">
          <span className="wordmark">MyBooks</span>
          {!phone && (
            <nav aria-label="Pages">
              <Link to="/" activeOptions={{ exact: true }}>
                Library
              </Link>
              <Link to="/add">Add a book</Link>
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
      <UpdateNotice watcher={updates} store={store} publisher={publisher} />
    </ToastProvider>
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
  return (
    <PageBoundary>
      <Suspense fallback={loadingPage}>
        <YearPage
          store={store}
          onOpen={(id) => void navigate({ to: '/book/$id', params: { id } })}
          onLibrary={() => void navigate({ to: '/' })}
        />
      </Suspense>
    </PageBoundary>
  )
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
  return <BookPage store={store} owner={owner} id={id} onBack={() => void navigate({ to: '/' })} />
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
const bookRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/book/$id',
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
  component: YearRoute,
})

const newsRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/news',
  beforeLoad: requireSession,
  component: NewsRoute,
})

const routeTree = rootRoute.addChildren([signInRoute, libraryRoute, bookRoute, addRoute, importRoute, tidyRoute, yearRoute, newsRoute])

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
