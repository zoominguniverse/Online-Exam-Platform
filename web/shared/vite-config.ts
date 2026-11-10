import { fileURLToPath } from 'node:url'
import vue from '@vitejs/plugin-vue'
import { defineConfig } from 'vite'

export function clientConfig(url: URL, base: string, port: number) {
  const root = fileURLToPath(url)
  const proxy = { '/api': process.env.EXAM_API_ORIGIN || 'http://127.0.0.1:8081' }
  return defineConfig({
    root,
    base,
    plugins: [vue()],
    server: { host: '127.0.0.1', port, strictPort: true, proxy },
    preview: { host: '127.0.0.1', port, strictPort: true, proxy },
  })
}
