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
    test: {
      environment: 'jsdom',
      globalSetup: ['./src/test-global-setup.ts'],
      setupFiles: ['./src/test-setup.ts'],
      globals: false,
    },
  }
})
