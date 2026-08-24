import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { build } from 'esbuild'
import path from 'node:path'

const rootDir = path.resolve(import.meta.dirname, '..')
const { installChunkLoadRecovery } = await build({
  entryPoints: [path.join(rootDir, 'src/lib/chunk-load-recovery.ts')],
  bundle: true,
  platform: 'node',
  format: 'esm',
  write: false,
}).then((result) => {
  const code = result.outputFiles[0].text
  return import(`data:text/javascript,${encodeURIComponent(code)}`)
})

test('reloads once when a Vite lazy chunk no longer exists', () => {
  const listeners = new Map()
  const storage = new Map()
  let reloads = 0
  const target = {
    addEventListener(type, listener) {
      listeners.set(type, listener)
    },
    sessionStorage: {
      getItem(key) {
        return storage.get(key) ?? null
      },
      setItem(key, value) {
        storage.set(key, value)
      },
    },
    location: {
      reload() {
        reloads += 1
      },
    },
  }

  installChunkLoadRecovery(target, () => 20_000)
  const firstEvent = { prevented: false, preventDefault() { this.prevented = true } }
  const repeatedEvent = { prevented: false, preventDefault() { this.prevented = true } }
  listeners.get('vite:preloadError')(firstEvent)
  listeners.get('vite:preloadError')(repeatedEvent)

  assert.equal(firstEvent.prevented, true)
  assert.equal(repeatedEvent.prevented, false)
  assert.equal(reloads, 1)
})

test('production nginx never caches the SPA entry document', async () => {
  const config = await readFile(
    path.join(rootDir, 'deploy/nginx/admin.conf.tpl'),
    'utf8'
  )

  assert.match(config, /location = \/index\.html/)
  assert.match(config, /Cache-Control "no-store, no-cache, must-revalidate"/)
})
