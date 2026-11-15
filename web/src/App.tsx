import {
  Outlet,
  RouterProvider,
  createRootRouteWithContext,
  createRoute,
  createRouter,
  redirect,
} from '@tanstack/react-router'
import { SignIn } from './auth/SignIn'
import { useSession } from './auth/useSession'

interface RouterContext {
  signedIn: boolean
}

function page(title: string) {
  return function Page() {
    return (
      <main>
        <h1>{title}</h1>
      </main>
    )
  }
}

const rootRoute = createRootRouteWithContext<RouterContext>()({ component: Outlet })

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
  component: page('Library'),
})
const bookRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/book/$id',
  beforeLoad: requireSession,
  component: page('Book'),
})
const addRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/add',
  beforeLoad: requireSession,
  component: page('Add a book'),
})
const importRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/import',
  beforeLoad: requireSession,
  component: page('Import'),
})
const tidyRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/tidy',
  beforeLoad: requireSession,
  component: page('Tidy up'),
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

export function App() {
  const { session, loading } = useSession()
  if (loading) return <p className="loading">Loading…</p>
  return <RouterProvider key={session ? 'in' : 'out'} router={router} context={{ signedIn: session !== null }} />
}
