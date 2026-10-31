import react from '@vitejs/plugin-react'
import { defineConfig } from 'vitest/config'

export default defineConfig({
  plugins: [react()],
  test: {
    projects: [
      { test: { name: 'api', environment: 'node', include: ['src/**/*.test.ts'] } },
      { test: { name: 'ui', environment: 'jsdom', include: ['src/**/*.test.tsx'] } },
    ],
  },
})
