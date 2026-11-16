/// <reference types="vitest/config" />
import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig({
  base: '/MyBooksNewGeneration/',
  plugins: [react()],
  test: {
    environment: 'jsdom',
    globalSetup: ['./src/test-global-setup.ts'],
    setupFiles: ['./src/test-setup.ts'],
    globals: false,
  },
})
