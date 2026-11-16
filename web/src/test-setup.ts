import '@testing-library/jest-dom/vitest'
import { cleanup } from '@testing-library/react'
import { afterEach } from 'vitest'

afterEach(() => cleanup())

// Nothing in a test reaches the network: a test that needs a response injects its own fetch.
globalThis.fetch = () => Promise.reject(new Error('The network is not available in tests.'))
