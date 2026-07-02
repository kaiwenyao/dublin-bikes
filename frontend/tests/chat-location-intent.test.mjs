import test from 'node:test'
import assert from 'node:assert/strict'
import { build } from 'esbuild'
import path from 'node:path'

const rootDir = path.resolve(import.meta.dirname, '..')
const { needsCurrentLocation } = await build({
  entryPoints: [path.join(rootDir, 'src/lib/chat-location-intent.ts')],
  bundle: true,
  platform: 'node',
  format: 'esm',
  write: false,
}).then((result) => {
  const code = result.outputFiles[0].text
  const moduleUrl = `data:text/javascript,${encodeURIComponent(code)}`
  return import(moduleUrl)
})

test('detects nearby bike station requests', () => {
  assert.equal(needsCurrentLocation('看一下我最近的车站是哪个'), true)
  assert.equal(needsCurrentLocation('Show nearby bike stations'), true)
  assert.equal(needsCurrentLocation('What is the closest Dublin Bikes dock?'), true)
})

test('does not request location for unrelated messages', () => {
  assert.equal(needsCurrentLocation('你好'), false)
  assert.equal(needsCurrentLocation('最近有什么新闻'), false)
  assert.equal(needsCurrentLocation('Tell me about stations in general'), false)
})
