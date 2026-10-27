import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';

// En desarrollo, /api se reenvía a la API de Spring; en Compose lo hace nginx.
export default defineConfig({
  plugins: [react()],
  server: {
    port: 8088,
    proxy: {
      '/api': { target: 'http://localhost:8087', changeOrigin: true },
      // Mandos del mock de la Rama (puerto 8089): /mock/__admin/... → /__admin/...
      '/mock': { target: 'http://localhost:8089', changeOrigin: true, rewrite: (ruta) => ruta.replace(/^\/mock/, '') },
    },
  },
  test: { environment: 'node' },
});
