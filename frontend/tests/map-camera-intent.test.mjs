import test from 'node:test'
import assert from 'node:assert/strict'
import { build } from 'esbuild'
import path from 'node:path'

const rootDir = path.resolve(import.meta.dirname, '..')
const { createMapCameraIntent } = await build({
  entryPoints: [path.join(rootDir, 'src/lib/map-camera-intent.ts')],
  bundle: true,
  platform: 'node',
  format: 'esm',
  write: false,
}).then((result) => {
  const code = result.outputFiles[0].text
  const moduleUrl = `data:text/javascript,${encodeURIComponent(code)}`
  return import(moduleUrl)
})

test('allows an uncontested location request to recenter exactly once', () => {
  const intent = createMapCameraIntent()
  const requestId = intent.beginRecenterRequest()

  intent.completeRecenterRequest(requestId)

  assert.equal(intent.consumePendingRecenter(), true)
  assert.equal(intent.consumePendingRecenter(), false)
})

test('keeps the user camera position when a drag happens during location lookup', () => {
  const intent = createMapCameraIntent()
  const requestId = intent.beginRecenterRequest()

  intent.cancelPendingRecenter()
  intent.completeRecenterRequest(requestId)

  assert.equal(intent.consumePendingRecenter(), false)
})

test('keeps the user camera position when a drag happens after location resolves', () => {
  const intent = createMapCameraIntent()
  const requestId = intent.beginRecenterRequest()

  intent.completeRecenterRequest(requestId)
  intent.cancelPendingRecenter()

  assert.equal(intent.consumePendingRecenter(), false)
})

test('allows a newer locate request after an earlier drag', () => {
  const intent = createMapCameraIntent()
  const automaticRequestId = intent.beginRecenterRequest()
  intent.cancelPendingRecenter()
  intent.completeRecenterRequest(automaticRequestId)

  const explicitRequestId = intent.beginRecenterRequest()
  intent.completeRecenterRequest(explicitRequestId)

  assert.equal(intent.consumePendingRecenter(), true)
})

test('ignores a stale location result after a newer request starts', () => {
  const intent = createMapCameraIntent()
  const firstRequestId = intent.beginRecenterRequest()
  const secondRequestId = intent.beginRecenterRequest()

  intent.completeRecenterRequest(firstRequestId)
  assert.equal(intent.consumePendingRecenter(), false)

  intent.completeRecenterRequest(secondRequestId)
  assert.equal(intent.consumePendingRecenter(), true)
})
