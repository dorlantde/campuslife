import { defineConfig, loadEnv, type ProxyOptions } from 'vite'
import vue from '@vitejs/plugin-vue'
import { readFileSync, writeFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '')
  const target = env.CAMPUSLIFE_API_TARGET || 'http://127.0.0.1:8080'
  const url = new URL(target)
  if (url.protocol !== 'http:' || url.hostname !== '127.0.0.1' || url.username || url.password || url.pathname !== '/' || url.search || url.hash) {
    throw new Error('CAMPUSLIFE_API_TARGET must be an HTTP loopback origin')
  }
  const proxy: ProxyOptions = { target, changeOrigin: false }
  const faultOutput = process.env.CAMPUSLIFE_FRONTEND_FAULT_OUTPUT
  if (faultOutput) {
    // Opt-in QA fault, exclusively inside a script-owned disposable preview; absent in normal Run/Build.
    const parent = resolve(process.cwd(), '../.cache/frontend-it')
    if (dirname(resolve(faultOutput)) !== parent || !/^[a-f0-9]{32}$/.test(resolve(faultOutput).slice(parent.length + 1))) throw new Error('Invalid disposable fault evidence directory')
    const ready = JSON.parse(readFileSync(resolve(faultOutput, 'ready.json'), 'utf8'))
    if (ready.databaseName !== 'campuslife_it' || String(ready.backendPort) !== url.port) throw new Error('Fault injection requires matching disposable backend')
    proxy.selfHandleResponse = true
    proxy.configure = server => {
      let dropped = false
      server.on('proxyRes', (upstream, request, response) => {
        if (!dropped && request.method === 'POST' && request.url === '/api/v1/orders/prints' && (upstream.statusCode || 0) >= 200 && (upstream.statusCode || 0) < 300) {
          dropped = true
          const chunks: Buffer[] = []
          upstream.on('data', (chunk: Buffer) => chunks.push(chunk))
          upstream.on('end', () => {
            const result = JSON.parse(Buffer.concat(chunks).toString('utf8'))
            writeFileSync(resolve(faultOutput, 'dropped-print-response.json'), JSON.stringify({ occurredAt: new Date().toISOString(), orderId: result.data.id, payableAmount: result.data.payableAmount, key: request.headers['idempotency-key'], note: 'Backend committed a successful create; QA proxy dropped its response once. No token/password recorded.' }, null, 2))
            response.destroy()
          })
        } else {
          response.writeHead(upstream.statusCode || 502, upstream.headers)
          upstream.pipe(response)
        }
      })
    }
  }
  return {
    plugins: [vue()],
    server: { host: '127.0.0.1', port: 5173, strictPort: true, proxy: { '/api/v1': proxy } },
    test: { environment: 'node', include: ['tests/**/*.test.ts'] },
  }
})
