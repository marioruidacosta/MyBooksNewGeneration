import { useCallback, useContext, useMemo } from 'react'
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
import { ImportPage } from './io/ImportPage'
import { LibraryPage } from './library/LibraryPage'
import { supabase } from './supabase'
import { TidyPage } from './tidy/TidyPage'
import { createSupabaseSyncApi, type SyncApi } from './sync/api'
import { SaveBadge } from './sync/SaveBadge'
import { LibraryStore } from './sync/store'
import { StoreContext, useOptionalStore, useStore } from './sync/StoreContext'
import { useLibraryStore, useWarnBeforeClosing } from './sync/useLibraryStore'

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
  return <ImportPage store={store} owner={covers?.owner} storage={covers?.storage} />
}

function TidyRoute() {
  const store = useStore()
  const covers = useContext(CoverContext)
  if (!covers) throw new Error('TidyRoute: no signed-in account')
  return <TidyPage store={store} owner={covers.owner} storage={supabase.storage} />
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
  const { store, error } = useLibraryStore(userId, (id) => new LibraryStore({ api: createApi(id), owner: id }))
  useWarnBeforeClosing(store)
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
      <p className="loading">Loading…</p>
    )
  }
  return (
    <StoreContext.Provider value={store}>
      <CoverContext.Provider value={covers}>
        <RouterProvider key={session ? 'in' : 'out'} router={router} context={{ signedIn: session !== null }} />
      </CoverContext.Provider>
    </StoreContext.Provider>
  )
}
