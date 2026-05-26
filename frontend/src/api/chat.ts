import { fetchEventSource } from '@microsoft/fetch-event-source'
import { API_BASE_URL } from '@/config'
import { CHAT_ENDPOINTS } from './endpoints'
import request, { refreshAccessToken } from './request'
import { getAccessToken } from './token'

export async function deleteChatSessionAPI(sessionId: string): Promise<void> {
  await request.delete(`${CHAT_ENDPOINTS.sessions}/${encodeURIComponent(sessionId)}`)
}

export interface ChatStreamOptions {
  chat_id: string
  message: string
  signal?: AbortSignal
  onMessage: (chunk: string) => void
  onDone?: () => void
  onError?: (error: Error) => void
}

export interface ChatSession {
  id: string
  created_at: string
  title: string
  updated_at?: string
}

export interface ChatMessageDTO {
  content: string
  role: 'user' | 'assistant'
}

function normalizeChatRole(value: unknown): ChatMessageDTO['role'] {
  const role = String(value ?? '').toLowerCase()
  if (role === 'user' || role === 'human') return 'user'
  return 'assistant'
}

function normalizeChatSession(raw: unknown): ChatSession | null {
  if (!raw || typeof raw !== 'object') return null
  const record = raw as Record<string, unknown>
  const id = String(record.id ?? record.session_id ?? '').trim()
  if (!id) return null
  return {
    id,
    title: String(record.title ?? ''),
    created_at: String(record.created_at ?? ''),
    updated_at:
      record.updated_at != null ? String(record.updated_at) : undefined,
  }
}

function normalizeChatMessages(payload: unknown): ChatMessageDTO[] {
  const rawList = Array.isArray(payload)
    ? payload
    : payload &&
        typeof payload === 'object' &&
        Array.isArray((payload as { messages?: unknown }).messages)
      ? (payload as { messages: unknown[] }).messages
      : []

  return rawList
    .map((item) => {
      if (!item || typeof item !== 'object') return null
      const record = item as Record<string, unknown>
      const content = String(record.content ?? '').trim()
      if (!content) return null
      return {
        role: normalizeChatRole(record.role),
        content,
      }
    })
    .filter((item): item is ChatMessageDTO => item != null)
}

const RETRY_AFTER_REFRESH = 'RETRY_AFTER_REFRESH'
const isRetryAfterRefreshError = (error: Error): boolean =>
  error.message === RETRY_AFTER_REFRESH
const CHAT_AUTH_FAILURE_MESSAGE = 'Session expired. Please sign in again.'
const STREAM_EMPTY_MESSAGE = 'Stream closed before any response content.'
const isCompletionMarker = (chunk: string): boolean => {
  const normalized = chunk.trim()
  return normalized === '[DONE]' || normalized === '"[DONE]"'
}

function openStream(
  url: string,
  token: string,
  options: ChatStreamOptions,
  signal?: AbortSignal
): Promise<void> {
  const { chat_id, message, onMessage, onDone, onError } = options
  const headers: Record<string, string> = {
    'Content-Type': 'application/json',
    Authorization: `Bearer ${token}`,
  }
  return new Promise((resolve, reject) => {
    let completed = false
    let receivedContent = false
    let settled = false
    let doneNotified = false

    const resolveOnce = () => {
      if (settled) return
      settled = true
      resolve()
    }

    const rejectOnce = (error: Error, notify = true) => {
      if (settled) return
      settled = true
      if (notify) onError?.(error)
      reject(error)
    }

    const notifyDoneOnce = () => {
      if (doneNotified) return
      doneNotified = true
      onDone?.()
      resolveOnce()
    }

    fetchEventSource(url, {
      method: 'POST',
      headers,
      body: JSON.stringify({ chat_id, message }),
      signal: signal ?? undefined,
      openWhenHidden: true,
      async onopen(response) {
        if (response.ok) return
        if (response.status === 401 || response.status === 403) {
          throw new Error(RETRY_AFTER_REFRESH)
        }
        throw new Error(response.statusText || `HTTP ${response.status}`)
      },
      onmessage(ev) {
        if (ev.data == null) return
        if (isCompletionMarker(ev.data)) {
          completed = true
          onMessage(ev.data)
          notifyDoneOnce()
          return
        }
        receivedContent = true
        onMessage(ev.data)
      },
      onclose() {
        if (!completed && !receivedContent) {
          rejectOnce(new Error(STREAM_EMPTY_MESSAGE))
          return
        }
        notifyDoneOnce()
        resolveOnce()
      },
      onerror(err) {
        const normalizedError = err instanceof Error ? err : new Error(String(err))
        if (isRetryAfterRefreshError(normalizedError)) {
          throw normalizedError
        }
        // Throwing here stops fetchEventSource auto-retry so outer lifecycle stays consistent.
        throw normalizedError
      },
    }).catch((err) => {
      const normalizedError = err instanceof Error ? err : new Error(String(err))
      if (isRetryAfterRefreshError(normalizedError)) {
        rejectOnce(normalizedError, false)
        return
      }
      rejectOnce(normalizedError)
    })
  })
}

/**
 * Call /api/chat/stream streaming endpoint with JWT. If the first stream attempt
 * used an existing access token and gets 401, refresh once and retry once.
 */
export async function chatStreamAPI(options: ChatStreamOptions): Promise<void> {
  const { signal } = options
  let refreshedBeforeFirstStream = false
  let token = getAccessToken()
  if (!token) {
    token = await refreshAccessToken()
    refreshedBeforeFirstStream = token != null
  }
  if (!token) {
    const err = new Error('Please sign in first.')
    options.onError?.(err)
    throw err
  }

  const url = `${API_BASE_URL || ''}${CHAT_ENDPOINTS.stream}`.trim() || CHAT_ENDPOINTS.stream

  try {
    await openStream(url, token, options, signal)
  } catch (err) {
    if (err instanceof Error && isRetryAfterRefreshError(err)) {
      if (refreshedBeforeFirstStream) {
        const authError = new Error(CHAT_AUTH_FAILURE_MESSAGE)
        options.onError?.(authError)
        throw authError
      }
      token = await refreshAccessToken()
      if (!token) {
        const authError = new Error(CHAT_AUTH_FAILURE_MESSAGE)
        options.onError?.(authError)
        throw authError
      }
      try {
        await openStream(url, token, options, signal)
        return
      } catch (retryErr) {
        if (retryErr instanceof Error && isRetryAfterRefreshError(retryErr)) {
          const authError = new Error(CHAT_AUTH_FAILURE_MESSAGE)
          options.onError?.(authError)
          throw authError
        }
        throw retryErr
      }
    }
    throw err
  }
}

export async function getChatSessionsAPI(): Promise<ChatSession[]> {
  const { data } = await request.get<unknown>(CHAT_ENDPOINTS.sessions)
  const list = Array.isArray(data) ? data : []
  return list
    .map(normalizeChatSession)
    .filter((session): session is ChatSession => session != null)
}

export async function getChatSessionMessagesAPI(
  sessionId: string
): Promise<ChatMessageDTO[]> {
  const { data } = await request.get<unknown>(
    `${CHAT_ENDPOINTS.sessions}/${encodeURIComponent(sessionId)}/messages`
  )
  return normalizeChatMessages(data)
}
