import { configDefaults, defineConfig } from 'vitest/config'
import react from '@vitejs/plugin-react'
import type { Plugin } from 'vite'

function healthzPlugin(): Plugin {
  const respond = (res: { setHeader(name: string, value: string): void; end(body: string): void }) => {
    res.setHeader('Access-Control-Allow-Origin', '*')
    res.setHeader('Cache-Control', 'no-store')
    res.setHeader('Content-Type', 'text/plain; charset=utf-8')
    res.end('ok\n')
  }
  return {
    name: 'marketing-healthz',
    configureServer(server) {
      server.middlewares.use((req, res, next) => {
        if (req.url?.split('?')[0] !== '/healthz') return next()
        respond(res)
      })
    },
    configurePreviewServer(server) {
      server.middlewares.use((req, res, next) => {
        if (req.url?.split('?')[0] !== '/healthz') return next()
        respond(res)
      })
    },
  }
}

export default defineConfig({
  plugins: [react(), healthzPlugin(), {
    name: 'marketing-config-js-first',
    transformIndexHtml(html) {
      const tag = '<script src="/config.js"></script>'
      return html.replace(tag, '').replace('<head>', `<head>\n    ${tag}`)
    },
  }],
  server: {
    host: '127.0.0.1',
    port: 4173,
    proxy: { '/api': { target: 'http://127.0.0.1:8080', changeOrigin: true } },
  },
  preview: { host: '127.0.0.1', port: 4173 },
  build: {
    rollupOptions: {
      output: {
        manualChunks(id) {
          if (id.includes('@xyflow/react')) return 'vendor-flow'
          if (id.includes('recharts')) return 'vendor-charts'
          if (id.includes('react-router') || id.includes('@tanstack/react-query')) return 'vendor-app'
          return undefined
        },
      },
    },
  },
  test: {
    environment: 'jsdom',
    setupFiles: './src/test/setup.ts',
    css: true,
    exclude: [...configDefaults.exclude, 'e2e/**'],
  },
})
