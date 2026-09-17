import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';

export default defineConfig({
  plugins: [react()],
  test: {
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.ts'],
    include: ['integration/**/*.test.tsx'],
    maxWorkers: 1,
    fileParallelism: false,
    testTimeout: 90_000,
  },
});
