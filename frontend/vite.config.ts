import fs from 'node:fs'
import path from 'node:path'
import tailwindcss from '@tailwindcss/vite'
import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

// Lokalny HTTPS na https://intermanage.lvh.me:5173 (lvh.me i *.lvh.me wskazują na 127.0.0.1).
// Certyfikat z mkcert: `npm run certs`. Bez niego serwer startuje po HTTP.
const certDir = path.resolve(import.meta.dirname, 'certs')
const certFile = path.join(certDir, 'lvh.me.pem')
const keyFile = path.join(certDir, 'lvh.me-key.pem')
const https = fs.existsSync(certFile) && fs.existsSync(keyFile)
  ? { cert: fs.readFileSync(certFile), key: fs.readFileSync(keyFile) }
  : undefined

// https://vite.dev/config/
export default defineConfig({
  plugins: [react(), tailwindcss()],
  resolve: {
    alias: {
      '@': path.resolve(import.meta.dirname, './src'),
    },
  },
  server: {
    host: '127.0.0.1',
    allowedHosts: ['.lvh.me'],
    https,
    // Backend Spring na :8080. Jeden origin = ciasteczka sesji i CSRF działają bez CORS.
    // API_PROXY_TARGET pozwala podpiąć drugi backend (np. podgląd na innym porcie).
    proxy: {
      '/api': process.env.API_PROXY_TARGET ?? 'http://localhost:8080',
    },
  },
})
