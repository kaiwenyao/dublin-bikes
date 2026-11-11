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

const { appendToAssistant, dropTrailingEmptyAssistant } = await loadTs(
  'src/lib/chat-messages.ts'
)
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

function createThread() {
  let messages = [
    { id: 'user-1', role: 'user', content: 'hi' },
    { id: 'assistant-1', role: 'assistant', content: '' },
  ]
  const scheduler = manualScheduler()
  const batcher = createStreamBatcher(
    (text) => {
      messages = appendToAssistant(messages, 'assistant-1', text)
    },
    { setTimer: scheduler.setTimer, clearTimer: scheduler.clearTimer }
  )
  return {
    batcher,
    scheduler,
    get messages() {
      return messages
    },
    set messages(next) {
      messages = next
    },
  }
}

test('stopping generation flushes the buffered chunk before dropping the bubble', () => {
  const thread = createThread()

  // First chunk arrived but the 50ms flush has not run yet, so the bubble still looks empty.
  thread.batcher.push('Hel')
  assert.equal(thread.messages[1].content, '')

  // handleStopGeneration: flush the active batcher, then drop a still-empty trailing bubble.
  thread.batcher.flush()
  thread.messages = dropTrailingEmptyAssistant(thread.messages)

  assert.equal(thread.messages.length, 2)
  assert.equal(thread.messages[1].content, 'Hel')
})

test('dropping the bubble before flushing would lose the first chunk', () => {
  const thread = createThread()

  thread.batcher.push('Hel')
  const dropped = dropTrailingEmptyAssistant(thread.messages)

  // Documents the regression: the guard only works when the flush happens first.
  assert.equal(dropped.length, 1)
  assert.equal(thread.messages[1].content, '')
})

test('keeps a bubble that already rendered content', () => {
  const thread = createThread()
  thread.messages = appendToAssistant(thread.messages, 'assistant-1', 'Hello')

  assert.equal(dropTrailingEmptyAssistant(thread.messages).length, 2)
})

test('does not touch a thread whose last message is not an empty assistant bubble', () => {
  const thread = createThread()
  thread.messages = thread.messages.slice(0, 1)

  assert.equal(dropTrailingEmptyAssistant(thread.messages).length, 1)
})
