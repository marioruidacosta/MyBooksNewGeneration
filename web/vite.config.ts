/// <reference types="vitest/config" />
import { defineConfig, loadEnv } from 'vite'
import react from '@vitejs/plugin-react'
import { requireBuildEnv } from './src/build-env'

export default defineConfig(({ command, mode }) => {
  // A production build without the Supabase settings would deploy a site that cannot sign in: fail it instead.
  if (command === 'build') requireBuildEnv(mode, { ...loadEnv(mode, process.cwd(), 'VITE_'), ...process.env })
  return {
    base: '/MyBooksNewGeneration/app/',
    plugins: [react()],
    build: {
      rollupOptions: {
        output: {
          // The libraries change rarely: their own files stay cached across deploys, and none passes the size warning.
          manualChunks(id: string) {
            if (id.includes('/node_modules/@supabase/')) return 'supabase'
            if (id.includes('/node_modules/@tanstack/')) return 'tanstack'
            return undefined
          },
        },
      },
    },
    test: {
      environment: 'jsdom',
      globalSetup: ['./src/test-global-setup.ts'],
      setupFiles: ['./src/test-setup.ts'],
      globals: false,
    },
  }
})
