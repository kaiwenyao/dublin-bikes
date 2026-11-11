import test from 'node:test'
import assert from 'node:assert/strict'
import { build } from 'esbuild'
import path from 'node:path'

const rootDir = path.resolve(import.meta.dirname, '..')
const { createStreamBatcher } = await build({
  entryPoints: [path.join(rootDir, 'src/lib/stream-batcher.ts')],
  bundle: true,
  platform: 'node',
  format: 'esm',
  write: false,
}).then((result) => {
  const code = result.outputFiles[0].text
  const moduleUrl = `data:text/javascript,${encodeURIComponent(code)}`
  return import(moduleUrl)
})

function manualScheduler() {
  const timers = []
  return {
    timers,
    setTimer(callback) {
      timers.push(callback)
      return timers.length
    },
    clearTimer(handle) {
      timers[handle - 1] = null
    },
    runAll() {
      for (const callback of timers) {
        if (callback) callback()
      }
    },
  }
}

test('coalesces many chunks into one flush', () => {
  const scheduler = manualScheduler()
  const flushes = []
  const batcher = createStreamBatcher((text) => flushes.push(text), {
    setTimer: scheduler.setTimer,
    clearTimer: scheduler.clearTimer,
  })

  batcher.push('Hello')
  batcher.push(', ')
  batcher.push('world')

  assert.deepEqual(flushes, [])
  scheduler.runAll()
  assert.deepEqual(flushes, ['Hello, world'])
})

test('schedules a new flush after the previous one fired', () => {
  const scheduler = manualScheduler()
  const flushes = []
  const batcher = createStreamBatcher((text) => flushes.push(text), {
    setTimer: scheduler.setTimer,
    clearTimer: scheduler.clearTimer,
  })

  batcher.push('a')
  scheduler.runAll()
  batcher.push('b')
  scheduler.runAll()

  assert.deepEqual(flushes, ['a', 'b'])
})

test('flush renders buffered text immediately and cancels the timer', () => {
  const scheduler = manualScheduler()
  const flushes = []
  const batcher = createStreamBatcher((text) => flushes.push(text), {
    setTimer: scheduler.setTimer,
    clearTimer: scheduler.clearTimer,
  })

  batcher.push('done')
  batcher.flush()

  assert.deepEqual(flushes, ['done'])
  // The cancelled timer must not produce a second, empty flush.
  scheduler.runAll()
  assert.deepEqual(flushes, ['done'])
})

test('flush without buffered text is a no-op', () => {
  const scheduler = manualScheduler()
  const flushes = []
  const batcher = createStreamBatcher((text) => flushes.push(text), {
    setTimer: scheduler.setTimer,
    clearTimer: scheduler.clearTimer,
  })

  batcher.flush()

  assert.deepEqual(flushes, [])
})

test('cancel discards buffered text', () => {
  const scheduler = manualScheduler()
  const flushes = []
  const batcher = createStreamBatcher((text) => flushes.push(text), {
    setTimer: scheduler.setTimer,
    clearTimer: scheduler.clearTimer,
  })

  batcher.push('discard me')
  batcher.cancel()
  scheduler.runAll()

  assert.deepEqual(flushes, [])
})
