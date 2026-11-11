import test from 'node:test'
import assert from 'node:assert/strict'
import { build } from 'esbuild'
import path from 'node:path'

const rootDir = path.resolve(import.meta.dirname, '..')

async function loadTs(relativePath) {
  const result = await build({
    entryPoints: [path.join(rootDir, relativePath)],
    bundle: true,
    platform: 'node',
    format: 'esm',
    write: false,
  })
  const code = result.outputFiles[0].text
  return import(`data:text/javascript,${encodeURIComponent(code)}`)
}

const { releaseRequest } = await loadTs('src/lib/chat-request.ts')
const { createStreamBatcher } = await loadTs('src/lib/stream-batcher.ts')

function manualScheduler() {
  let pending = null
  return {
    setTimer(callback) {
      pending = callback
      return 1
    },
    clearTimer() {
      pending = null
    },
    run() {
      const callback = pending
      pending = null
      if (callback) callback()
    },
  }
}

function trackedBatcher() {
  const flushed = []
  const scheduler = manualScheduler()
  const batcher = createStreamBatcher((text) => flushed.push(text), {
    setTimer: scheduler.setTimer,
    clearTimer: scheduler.clearTimer,
  })
  return { batcher, flushed, scheduler }
}

test('the active request owns the shared sending state', () => {
  const controller = new AbortController()
  const own = trackedBatcher()

  assert.equal(releaseRequest(controller, controller, own.batcher), 'active')
  // The owner path must not silently drop its own buffered text.
  own.batcher.push('tail')
  own.batcher.flush()
  assert.deepEqual(own.flushed, ['tail'])
})

test('a superseded request does not cancel the newer request buffer', () => {
  // A is waiting for geolocation, the user stops A and sends B, then A resolves late.
  const controllerA = new AbortController()
  const controllerB = new AbortController()
  const bufferA = trackedBatcher()
  const bufferB = trackedBatcher()

  bufferA.batcher.push('stale-A')
  // B is the active request now; A finishes late.
  const outcome = releaseRequest(controllerB, controllerA, bufferA.batcher)

  assert.equal(outcome, 'superseded')

  bufferB.batcher.push('answer-B')
  bufferB.batcher.flush()
  bufferA.batcher.flush()

  assert.deepEqual(bufferB.flushed, ['answer-B'])
  // A's own buffer was dropped by the release, so its late flush renders nothing.
  assert.deepEqual(bufferA.flushed, [])
})

test('stopping the active request keeps its own buffer available for a final flush', () => {
  const controller = new AbortController()
  const own = trackedBatcher()
  own.batcher.push('partial')

  // handleStopGeneration flushes first, then settles the request.
  own.batcher.flush()
  assert.equal(releaseRequest(controller, controller, own.batcher), 'active')
  assert.deepEqual(own.flushed, ['partial'])
})
