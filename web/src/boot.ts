import { restorePath } from './restore-path'

// Imported first by main.tsx: the router reads the address when App.tsx loads, so this must run before it.
restorePath()
