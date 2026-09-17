import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';

const proxy = { '/api': { target: 'http://127.0.0.1:8081', changeOrigin: false } };
export default defineConfig({
  plugins: [react()],
  server: { host: '127.0.0.1', port: 5173, strictPort: true, cors: false, proxy },
  preview: { host: '127.0.0.1', port: 4173, strictPort: true, cors: false, proxy },
  test: { environment: 'jsdom', setupFiles: ['./src/test/setup.ts'], maxWorkers: 1, fileParallelism: false },
});
