import { Suspense, lazy, useCallback, useContext, useEffect, useMemo, useState } from 'react'
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
import { useSession } from './auth/useSession'
import { AddPage } from './add/AddPage'
import { BookPage } from './book/BookPage'
import { CoverContext } from './covers/storage'
import { LibraryPage } from './library/LibraryPage'
import { PageBoundary } from './PageBoundary'
import { Publisher } from './share/publisher'
import { SharingContext } from './share/SharingContext'
import { supabase } from './supabase'
import { createSupabaseSyncApi, type SyncApi } from './sync/api'
import { SaveBadge } from './sync/SaveBadge'
import { LibraryStore } from './sync/store'
import { StoreContext, useOptionalStore, useStore } from './sync/StoreContext'
import { useLibraryStore, useWarnBeforeClosing } from './sync/useLibraryStore'

// The import and tidy pages (with the ZIP reader and the import code) load when first opened.
const ImportPage = lazy(() => import('./io/ImportPage').then((m) => ({ default: m.ImportPage })))
const TidyPage = lazy(() => import('./tidy/TidyPage').then((m) => ({ default: m.TidyPage })))

const loadingPage = <p className="loading">Loading…</p>

interface RouterContext {
  signedIn: boolean
}

function Shell() {
  const store = useOptionalStore()
  return (
    <>
      {store && (
        <header className="app-bar">
          <nav aria-label="Pages">
            <Link to="/">Library</Link>
            <Link to="/add">Add a book</Link>
            <Link to="/import">Import</Link>
            <Link to="/tidy">Tidy up</Link>
          </nav>
          <SaveBadge store={store} />
          <button type="button" onClick={() => void supabase.auth.signOut()}>
            Sign out
          </button>
        </header>
      )}
      <Outlet />
    </>
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
  return <AddPage store={store} onOpen={(id) => void navigate({ to: '/book/$id', params: { id } })} />
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

const routeTree = rootRoute.addChildren([signInRoute, libraryRoute, bookRoute, addRoute, importRoute, tidyRoute])

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
        <button type="button" onClick={() => void supabase.auth.signOut()}>
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
