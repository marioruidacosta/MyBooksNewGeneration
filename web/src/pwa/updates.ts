/**
 * The service worker's new versions, and the rule that they never reload the page by themselves: someone may be
 * typing, or have changes not yet sent. A new version installs and waits; [UpdateWatcher.available] says so, the person
 * chooses to reload, and if they never do it applies on the next cold start (a waiting worker takes over once every
 * page it could control is closed).
 */
export class UpdateWatcher {
  private waiting: ServiceWorker | null = null
  private applying = false
  private listeners = new Set<() => void>()

  constructor(
    private readonly container: ServiceWorkerContainer,
    private readonly reloadPage: () => void = () => window.location.reload(),
  ) {
    // The new worker took over because the person asked for it: only then does the page reload (once).
    container.addEventListener('controllerchange', () => {
      if (!this.applying) return
      this.applying = false
      this.reloadPage()
    })
  }

  /** Registers the worker at [url] (its folder is its scope) and starts watching it. */
  async register(url: string, scope: string): Promise<void> {
    const registration = await this.container.register(url, { scope })
    this.watch(registration)
    // The installed app is rarely reloaded: look for a new version whenever it comes back to the front.
    document.addEventListener('visibilitychange', () => {
      if (document.visibilityState === 'visible') registration.update().catch(() => {})
    })
  }

  /** Follows a registration: a version that finishes installing while another one runs becomes the waiting one. */
  watch(registration: ServiceWorkerRegistration): void {
    const hold = (worker: ServiceWorker | null) => {
      // With no controller this is the first install, not an update: nothing is running that could be replaced.
      if (worker && this.container.controller) this.setWaiting(worker)
    }
    hold(registration.waiting)
    registration.addEventListener('updatefound', () => {
      const incoming = registration.installing
      incoming?.addEventListener('statechange', () => {
        if (incoming.state === 'installed') hold(incoming)
      })
    })
  }

  private setWaiting(worker: ServiceWorker) {
    this.waiting = worker
    this.listeners.forEach((l) => l())
  }

  /** Whether a new version is waiting for the person's go-ahead. */
  get available(): boolean {
    return this.waiting !== null
  }

  subscribe = (cb: () => void): (() => void) => {
    this.listeners.add(cb)
    return () => {
      this.listeners.delete(cb)
    }
  }

  /** Lets the waiting version take over; the page reloads when it has. Only ever called on the person's say-so. */
  apply(): void {
    if (!this.waiting) return
    this.applying = true
    this.waiting.postMessage({ type: 'SKIP_WAITING' })
  }
}

/** The page's watcher; null on a browser without service workers. */
export const updates: UpdateWatcher | null =
  typeof navigator !== 'undefined' && 'serviceWorker' in navigator ? new UpdateWatcher(navigator.serviceWorker) : null

/** Registers the service worker (a production build on a browser that has them); the dev server has none. */
export function registerServiceWorker(): void {
  if (!import.meta.env.PROD || !updates) return
  const base = import.meta.env.BASE_URL
  void updates.register(`${base}sw.js`, base).catch((e: unknown) => console.warn('MyBooks: no offline copy this time', e))
}
