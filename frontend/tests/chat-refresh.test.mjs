import assert from 'node:assert/strict'
import { mkdtemp, writeFile } from 'node:fs/promises'
import http from 'node:http'
import { tmpdir } from 'node:os'
import path from 'node:path'
import { after, before, beforeEach, test } from 'node:test'
import { build } from 'esbuild'

const rootDir = path.resolve(import.meta.dirname, '..')

let server
let baseUrl
let chatStreamAPI
let validAccessToken
let refreshToken

const state = {
  refreshRequests: 0,
  streamRequests: 0,
  failNextStreamCount: 0,
  refreshDelayMs: 0,
}

class MemoryStorage {
  #items = new Map()

  getItem(key) {
    return this.#items.has(key) ? this.#items.get(key) : null
  }

  setItem(key, value) {
    this.#items.set(key, String(value))
  }

  removeItem(key) {
    this.#items.delete(key)
  }

  clear() {
    this.#items.clear()
  }
}

const base64Url = (value) =>
  Buffer.from(JSON.stringify(value))
    .toString('base64url')

const fakeJwt = ({ subject = '1', expiresInSeconds = 900 } = {}) =>
  `${base64Url({ alg: 'none', typ: 'JWT' })}.${base64Url({
    sub: subject,
    exp: Math.floor(Date.now() / 1000) + expiresInSeconds,
  })}.signature`

const readRequestBody = (request) =>
  new Promise((resolve, reject) => {
    const chunks = []
    request.on('data', (chunk) => chunks.push(chunk))
    request.on('end', () => {
      const raw = Buffer.concat(chunks).toString('utf8')
      resolve(raw ? JSON.parse(raw) : {})
    })
    request.on('error', reject)
  })

const writeJson = (response, body, statusCode = 200) => {
  response.writeHead(statusCode, { 'Content-Type': 'application/json' })
  response.end(JSON.stringify(body))
}

const createServer = () =>
  http.createServer(async (request, response) => {
    if (request.method === 'POST' && request.url === '/api/users/login') {
      await readRequestBody(request)
      writeJson(response, {
        code: 0,
        msg: 'ok',
        data: {
          access_token: validAccessToken,
          refresh_token: refreshToken,
          expires_in: 900,
          token_type: 'Bearer',
        },
      })
      return
    }

    if (request.method === 'POST' && request.url === '/api/users/refresh') {
      state.refreshRequests += 1
      await readRequestBody(request)
      if (state.refreshDelayMs > 0) {
        await new Promise((resolve) => setTimeout(resolve, state.refreshDelayMs))
      }
      validAccessToken = fakeJwt()
      writeJson(response, {
        code: 0,
        msg: 'ok',
        data: {
          access_token: validAccessToken,
          refresh_token: refreshToken,
          expires_in: 900,
          token_type: 'Bearer',
        },
      })
      return
    }

    if (request.method === 'POST' && request.url === '/api/chat/stream') {
      state.streamRequests += 1
      await readRequestBody(request)

      if (state.failNextStreamCount > 0) {
        state.failNextStreamCount -= 1
        response.writeHead(401, { 'Content-Type': 'application/json' })
        response.end(JSON.stringify({ code: 40101, msg: 'unauthorized', data: null }))
        return
      }

      response.writeHead(200, {
        'Content-Type': 'text/event-stream',
        'Cache-Control': 'no-cache',
      })
      response.end('data: {"content":"verified"}\n\ndata: [DONE]\n\n')
      return
    }

    response.writeHead(404)
    response.end()
  })

const installBrowserShims = () => {
  const localStorage = new MemoryStorage()
  const sessionStorage = new MemoryStorage()
  globalThis.window = {
    localStorage,
    sessionStorage,
    setTimeout,
    clearTimeout,
    location: { pathname: '/chat' },
    history: { replaceState() {} },
    dispatchEvent() {},
    fetch,
  }
  globalThis.document = {
    hidden: false,
    visibilityState: 'visible',
    addEventListener() {},
    removeEventListener() {},
  }
  globalThis.localStorage = localStorage
  globalThis.sessionStorage = sessionStorage
  globalThis.PopStateEvent = class PopStateEvent {}
}

const bundleChatApi = async () => {
  const tempDir = await mkdtemp(path.join(tmpdir(), 'dublin-bikes-chat-test-'))
  const configPath = path.join(tempDir, 'config.js')
  const sonnerPath = path.join(tempDir, 'sonner.js')
  const bundlePath = path.join(tempDir, 'chat-api.cjs')

  await writeFile(configPath, `export const API_BASE_URL = ${JSON.stringify(baseUrl)}\n`)
  await writeFile(sonnerPath, 'export const toast = { error() {}, warning() {}, success() {}, info() {} }\n')

  await build({
    entryPoints: [path.join(rootDir, 'src/api/chat.ts')],
    bundle: true,
    platform: 'node',
    format: 'cjs',
    outfile: bundlePath,
    alias: {
      '@/config': configPath,
      '@': path.join(rootDir, 'src'),
      sonner: sonnerPath,
    },
    logLevel: 'silent',
  })

  return import(`${bundlePath}?cache=${Date.now()}`)
}

before(async () => {
  server = createServer()
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve))
  baseUrl = `http://127.0.0.1:${server.address().port}`
  installBrowserShims()
  ;({ chatStreamAPI } = await bundleChatApi())
})

beforeEach(() => {
  state.refreshRequests = 0
  state.streamRequests = 0
  state.failNextStreamCount = 0
  state.refreshDelayMs = 0
  validAccessToken = fakeJwt()
  refreshToken = 'refresh-token'
  window.localStorage.clear()
  window.sessionStorage.clear()
})

after(async () => {
  await new Promise((resolve, reject) => {
    server.close((error) => (error ? reject(error) : resolve()))
  })
})

test('refreshes once before chat stream when access token is expired', async () => {
  window.localStorage.setItem('access_token', fakeJwt({ expiresInSeconds: -60 }))
  window.localStorage.setItem('refresh_token', refreshToken)

  let content = ''
  await chatStreamAPI({
    chat_id: 'expired_access',
    message: 'hello',
    onMessage(chunk) {
      if (chunk !== '[DONE]') content += chunk
    },
  })

  assert.equal(state.refreshRequests, 1)
  assert.equal(state.streamRequests, 1)
  assert.match(content, /verified/)
})

test('refreshes once and retries once when chat stream handshake returns 401', async () => {
  window.localStorage.setItem('access_token', validAccessToken)
  window.localStorage.setItem('refresh_token', refreshToken)
  state.failNextStreamCount = 1

  let content = ''
  await chatStreamAPI({
    chat_id: 'retry_after_401',
    message: 'hello',
    onMessage(chunk) {
      if (chunk !== '[DONE]') content += chunk
    },
  })

  assert.equal(state.refreshRequests, 1)
  assert.equal(state.streamRequests, 2)
  assert.match(content, /verified/)
})

test('does not refresh or retry again when a just-refreshed token gets a stream 401', async () => {
  window.localStorage.setItem('access_token', fakeJwt({ expiresInSeconds: -60 }))
  window.localStorage.setItem('refresh_token', refreshToken)
  state.failNextStreamCount = 1

  await assert.rejects(
    chatStreamAPI({
      chat_id: 'expired_access_then_401',
      message: 'hello',
      onMessage() {},
    }),
    /Session expired/
  )

  assert.equal(state.refreshRequests, 1)
  assert.equal(state.streamRequests, 1)
})

test('shares one refresh across concurrent chat stream requests', async () => {
  window.localStorage.setItem('access_token', fakeJwt({ expiresInSeconds: -60 }))
  window.localStorage.setItem('refresh_token', refreshToken)

  const send = async (chatId) => {
    let content = ''
    await chatStreamAPI({
      chat_id: chatId,
      message: 'hello',
      onMessage(chunk) {
        if (chunk !== '[DONE]') content += chunk
      },
    })
    return content
  }

  const [first, second] = await Promise.all([
    send('concurrent_one'),
    send('concurrent_two'),
  ])

  assert.equal(state.refreshRequests, 1)
  assert.equal(state.streamRequests, 2)
  assert.match(first, /verified/)
  assert.match(second, /verified/)
})

test('shares one refresh across concurrent chat stream handshake 401s', async () => {
  window.localStorage.setItem('access_token', validAccessToken)
  window.localStorage.setItem('refresh_token', refreshToken)
  state.failNextStreamCount = 2
  state.refreshDelayMs = 25

  const send = async (chatId) => {
    let content = ''
    await chatStreamAPI({
      chat_id: chatId,
      message: 'hello',
      onMessage(chunk) {
        if (chunk !== '[DONE]') content += chunk
      },
    })
    return content
  }

  const [first, second] = await Promise.all([
    send('concurrent_401_one'),
    send('concurrent_401_two'),
  ])

  assert.equal(state.refreshRequests, 1)
  assert.equal(state.streamRequests, 4)
  assert.match(first, /verified/)
  assert.match(second, /verified/)
})
