import { useState, useRef, useEffect } from 'react'
import {
  chatStreamAPI,
  type ChatSession,
  type ChatMessageDTO,
  getChatSessionsAPI,
  getChatSessionMessagesAPI,
  deleteChatSessionAPI,
} from '@/api/chat'
import { getAccessToken } from '@/api/token'
import { getMeAPI } from '@/api/user'
import { Button } from '@/components/ui/button'
import { toast } from 'sonner'

type Role = 'user' | 'assistant'

interface Message {
  id: string
  role: Role
  content: string
  createdAt: Date
}

const WELCOME_MESSAGE: Message = {
  id: 'welcome',
  role: 'assistant',
  content: "Hi! I'm your UCDSE assistant. Ask me about bike sharing, stations, or sustainable mobility—or just say hello.",
  createdAt: new Date(),
}

function createWelcomeMessage(): Message {
  return {
    ...WELCOME_MESSAGE,
    createdAt: new Date(),
  }
}

function createEmptyHistoryMessage(): Message {
  return {
    id: 'history-empty',
    role: 'assistant',
    content: 'No messages in this conversation yet.',
    createdAt: new Date(),
  }
}

function mapHistoryToMessages(sessionId: string, history: ChatMessageDTO[]): Message[] {
  return history.map((m, index) => ({
    id: `${m.role}-${index}-${sessionId}`,
    role: m.role,
    content: m.content,
    createdAt: new Date(),
  }))
}

/** Parse streaming response: each chunk from backend is {"content": "xxx"}, may end with [DONE] */
function parseStreamChunk(raw: string): string {
  const s = raw.trim()
  if (s === '[DONE]') return ''
  try {
    const obj = JSON.parse(s) as { content?: unknown }
    if (obj && typeof obj.content === 'string') return obj.content
    return s
  } catch {
    return raw
  }
}

function isAbortLikeError(error: unknown): boolean {
  if (error instanceof DOMException) {
    return error.name === 'AbortError'
  }
  if (error instanceof Error) {
    return error.name === 'AbortError' || error.message.toLowerCase().includes('aborted')
  }
  return false
}

function formatSessionTimestamp(createdAt: string): string {
  const date = new Date(createdAt)
  if (Number.isNaN(date.getTime())) return 'Unknown time'
  return date.toLocaleString('en-US', {
    month: 'short',
    day: 'numeric',
    hour: 'numeric',
    minute: '2-digit',
  })
}

function extractChatId(value: string): string {
  const normalizedValue = value.trim()
  if (!normalizedValue) return ''

  const marker = '_chat_'
  const markerIndex = normalizedValue.indexOf(marker)
  if (markerIndex >= 0) {
    return normalizedValue.slice(markerIndex + marker.length)
  }

  return normalizedValue
}

function findSessionIdByChatId(
  sessionList: ChatSession[],
  targetChatId: string
): string | null {
  const normalizedChatId = extractChatId(targetChatId)
  if (!normalizedChatId) return null

  const matchedSession = sessionList.find(
    (session) => extractChatId(session.id) === normalizedChatId
  )
  return matchedSession?.id ?? null
}

export default function Chat() {
  const [messages, setMessages] = useState<Message[]>([createWelcomeMessage()])
  const [input, setInput] = useState('')
  const [sending, setSending] = useState(false)
  const [chatId, setChatId] = useState<string>('')
  const [sessions, setSessions] = useState<ChatSession[]>([])
  const [sessionsLoading, setSessionsLoading] = useState(false)
  const [activeSessionId, setActiveSessionId] = useState<string | null>(null)
  const [loadingHistory, setLoadingHistory] = useState(false)
  const [deletingSessionId, setDeletingSessionId] = useState<string | null>(null)
  const [sessionPendingDelete, setSessionPendingDelete] = useState<ChatSession | null>(null)
  const deleteDialogRef = useRef<HTMLDialogElement>(null)
  const abortRef = useRef<AbortController | null>(null)
  const messageListRef = useRef<HTMLDivElement>(null)
  const chatIdSuffixRef = useRef<string>('')
  const chatIdInitPromiseRef = useRef<Promise<string> | null>(null)
  const historyRequestIdRef = useRef(0)
  const pendingScrollToBottomRef = useRef(false)
  const submitLockRef = useRef(false)
  const streamUnlockTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null)
  const isMountedRef = useRef(true)

  const handleChatPanelWheel = (e: React.WheelEvent<HTMLDivElement>) => {
    const list = messageListRef.current
    if (!list) return

    // Pointer inside chat panel: always consume wheel and scroll message list only.
    e.preventDefault()
    e.stopPropagation()
    list.scrollTop += e.deltaY
  }

  const scrollMessageListToBottom = () => {
    requestAnimationFrame(() => {
      requestAnimationFrame(() => {
        const el = messageListRef.current
        if (el) {
          el.scrollTop = el.scrollHeight
        }
      })
    })
  }

  const getChatIdSuffix = (): string => {
    if (chatIdSuffixRef.current) return chatIdSuffixRef.current
    const generatedSuffix =
      crypto.randomUUID?.()?.replace(/-/g, '').slice(0, 8) ??
      Math.random().toString(36).slice(2, 10)
    chatIdSuffixRef.current = generatedSuffix || 'chat'
    return chatIdSuffixRef.current
  }

  const buildChatId = (username?: string): string => {
    const normalizedName = username?.trim()
    return `${normalizedName || 'chat'}_${getChatIdSuffix()}`
  }

  const ensureChatId = async (): Promise<string> => {
    const currentChatId = extractChatId(chatId)
    if (currentChatId) return currentChatId

    if (chatIdInitPromiseRef.current) {
      return chatIdInitPromiseRef.current
    }

    const fallbackChatId = buildChatId()
    if (!getAccessToken()) {
      if (isMountedRef.current) {
        setChatId(fallbackChatId)
      }
      return fallbackChatId
    }

    const initPromise = getMeAPI()
      .then((me) => buildChatId(me?.username))
      .catch(() => fallbackChatId)
      .then((nextChatId) => {
        if (isMountedRef.current) {
          setChatId(nextChatId)
        }
        return nextChatId
      })
      .finally(() => {
        chatIdInitPromiseRef.current = null
      })

    chatIdInitPromiseRef.current = initPromise
    return initPromise
  }

  const loadSessions = async (
    {
      silent = false,
      activeChatId,
    }: { silent?: boolean; activeChatId?: string } = {}
  ) => {
    if (!silent) {
      setSessionsLoading(true)
    }

    try {
      const list = await getChatSessionsAPI()
      if (!isMountedRef.current) return
      setSessions(list ?? [])
      if (activeChatId) {
        setActiveSessionId(findSessionIdByChatId(list ?? [], activeChatId))
      }
    } catch (error) {
      // Global error toast already exists, just handle silently here
      console.error(error)
    } finally {
      if (isMountedRef.current && !silent) {
        setSessionsLoading(false)
      }
    }
  }

  useEffect(() => {
    isMountedRef.current = true
    void loadSessions()

    return () => {
      isMountedRef.current = false
      abortRef.current?.abort()
      abortRef.current = null
      submitLockRef.current = false
      setSending(false)
    }
  }, [])

  useEffect(() => {
    if (!pendingScrollToBottomRef.current || loadingHistory) return
    pendingScrollToBottomRef.current = false
    scrollMessageListToBottom()
  }, [messages, loadingHistory])

  const releaseSubmitLock = () => {
    submitLockRef.current = false
    abortRef.current = null
  }

  const clearStreamUnlockTimer = () => {
    if (streamUnlockTimerRef.current) {
      clearTimeout(streamUnlockTimerRef.current)
      streamUnlockTimerRef.current = null
    }
  }

  const scheduleStreamUnlock = () => {
    clearStreamUnlockTimer()
    streamUnlockTimerRef.current = setTimeout(() => {
      if (submitLockRef.current) {
        finishSending()
      }
    }, 2000)
  }

  const finishSending = () => {
    clearStreamUnlockTimer()
    setSending(false)
    releaseSubmitLock()
  }

  const handleSelectSession = async (session: ChatSession) => {
    abortRef.current?.abort()
    clearStreamUnlockTimer()
    setSending(false)
    releaseSubmitLock()

    const requestId = historyRequestIdRef.current + 1
    historyRequestIdRef.current = requestId
    const nextChatId = extractChatId(session.id)
    setActiveSessionId(session.id)
    setChatId(nextChatId)
    setMessages([])
    setLoadingHistory(true)
    try {
      const history = await getChatSessionMessagesAPI(session.id)
      if (!isMountedRef.current || requestId !== historyRequestIdRef.current) return
      const historyMessages = mapHistoryToMessages(session.id, history)
      pendingScrollToBottomRef.current = historyMessages.length > 0
      setMessages(
        historyMessages.length > 0 ? historyMessages : [createEmptyHistoryMessage()]
      )
    } catch (error) {
      if (!isMountedRef.current || requestId !== historyRequestIdRef.current) return
      pendingScrollToBottomRef.current = false
      const message =
        error instanceof Error ? error.message : 'Failed to load session messages. Please try again later.'
      toast.error(message)
    } finally {
      if (isMountedRef.current && requestId === historyRequestIdRef.current) {
        setLoadingHistory(false)
      }
    }
  }

  const handleStartNewChat = () => {
    historyRequestIdRef.current += 1
    setActiveSessionId(null)
    setChatId('')
    setInput('')
    setLoadingHistory(false)
    setMessages([createWelcomeMessage()])
    chatIdSuffixRef.current = ''
    chatIdInitPromiseRef.current = null
    setTimeout(() => {
      const el = messageListRef.current
      if (el) el.scrollTop = 0
    }, 0)
  }

  useEffect(() => {
    const dialog = deleteDialogRef.current
    if (!dialog) return
    if (sessionPendingDelete) {
      if (!dialog.open) dialog.showModal()
    } else if (dialog.open) {
      dialog.close()
    }
  }, [sessionPendingDelete])

  const requestDeleteSession = (session: ChatSession) => {
    if (deletingSessionId) return
    setSessionPendingDelete(session)
  }

  const cancelDeleteSession = () => {
    setSessionPendingDelete(null)
  }

  const confirmDeleteSession = async () => {
    const session = sessionPendingDelete
    if (!session || deletingSessionId) return

    setSessionPendingDelete(null)
    setDeletingSessionId(session.id)
    try {
      await deleteChatSessionAPI(session.id)
      if (!isMountedRef.current) return
      setSessions((prev) => prev.filter((s) => s.id !== session.id))
      if (activeSessionId === session.id) {
        abortRef.current?.abort()
        clearStreamUnlockTimer()
        finishSending()
        handleStartNewChat()
      }
      toast.success('Conversation deleted')
    } catch (error) {
      if (!isMountedRef.current) return
      const message = error instanceof Error ? error.message : 'Failed to delete conversation.'
      toast.error(message)
    } finally {
      if (isMountedRef.current) {
        setDeletingSessionId(null)
      }
    }
  }

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault()
    const text = input.trim()
    if (!text || sending || submitLockRef.current) return

    submitLockRef.current = true
    setSending(true)
    const controller = new AbortController()
    abortRef.current = controller

    let resolvedChatId: string
    try {
      resolvedChatId = await ensureChatId()
    } catch {
      finishSending()
      return
    }
    if (controller.signal.aborted || !isMountedRef.current) {
      finishSending()
      return
    }

    const userMessage: Message = {
      id: `user-${Date.now()}`,
      role: 'user',
      content: text,
      createdAt: new Date(),
    }
    const assistantId = `assistant-${Date.now()}`
    const assistantMessage: Message = {
      id: assistantId,
      role: 'assistant',
      content: '',
      createdAt: new Date(),
    }

    setMessages((prev) => [...prev, userMessage, assistantMessage])
    setInput('')

    try {
      await chatStreamAPI({
        chat_id: resolvedChatId,
        message: text,
        signal: controller.signal,
        onMessage(chunk) {
          if (controller.signal.aborted) return
          const trimmed = chunk.trim()
          if (trimmed === '[DONE]' || trimmed === '"[DONE]"') {
            finishSending()
            return
          }
          const part = parseStreamChunk(chunk)
          if (!part) return
          setMessages((prev) =>
            prev.map((m) =>
              m.id === assistantId ? { ...m, content: m.content + part } : m
            )
          )
          scheduleStreamUnlock()
          if (document.visibilityState === 'visible') {
            setTimeout(() => {
              const el = messageListRef.current
              if (el) el.scrollTop = el.scrollHeight
            }, 0)
          }
        },
        onDone() {
          finishSending()
          if (controller.signal.aborted) return
          void loadSessions({ silent: true, activeChatId: resolvedChatId })
        },
        onError(err) {
          if (controller.signal.aborted || isAbortLikeError(err)) {
            finishSending()
            return
          }
          setMessages((prev) =>
            prev.map((m) =>
              m.id === assistantId
                ? { ...m, content: `[Request failed] ${err.message}` }
                : m
            )
          )
          toast.error(err.message)
        },
      })
    } catch (err) {
      if (!controller.signal.aborted && !isAbortLikeError(err)) {
        setMessages((prev) =>
          prev.map((m) =>
            m.id === assistantId
              ? { ...m, content: `[Request failed] ${err instanceof Error ? err.message : 'Unknown error'}` }
              : m
          )
        )
        toast.error(err instanceof Error ? err.message : 'Request failed')
      }
    } finally {
      finishSending()
    }
  }

  return (
    <section className="h-screen w-full pt-24 pb-8 flex flex-col overflow-hidden">
      <div className="fixed inset-0 -z-10 overflow-hidden">
        <div className="absolute -top-40 -right-40 h-80 w-80 rounded-full bg-primary/10 blur-3xl" />
        <div className="absolute -bottom-40 -left-40 h-80 w-80 rounded-full bg-secondary/10 blur-3xl" />
      </div>

      <div className="mx-auto w-full max-w-5xl flex-1 min-h-0 flex flex-col px-4">
        <div className="mb-4 shrink-0">
          <div className="flex items-start justify-between gap-4">
            <div>
              <h1 className="text-2xl font-bold text-foreground">AI Chat</h1>
              <p className="mt-1 text-sm text-muted-foreground">
                Ask about bike sharing, stations, or anything UCDSE-related.
              </p>
            </div>

            <button
              type="button"
              onClick={handleStartNewChat}
              disabled={sending}
              className="inline-flex shrink-0 items-center gap-2 rounded-xl border border-[#00A8E8]/30 bg-white/80 px-3.5 py-2 text-sm font-medium text-[#007EA7] shadow-sm transition-all duration-200 hover:border-[#00A8E8]/50 hover:bg-[#00A8E8]/10 disabled:cursor-not-allowed disabled:opacity-60 md:hidden"
            >
              <svg
                className="h-4 w-4"
                viewBox="0 0 24 24"
                fill="none"
                stroke="currentColor"
                strokeWidth="2"
                strokeLinecap="round"
                strokeLinejoin="round"
              >
                <path d="M12 5v14" />
                <path d="M5 12h14" />
              </svg>
              New chat
            </button>
          </div>
        </div>

        <div className="flex-1 min-h-0 flex gap-4">
          <aside className="hidden md:flex w-72 shrink-0 flex-col overflow-hidden rounded-[28px] border border-white/45 bg-white/70 p-4 shadow-[0_20px_60px_rgba(0,52,89,0.12)] backdrop-blur-xl">
            <div className="rounded-2xl border border-white/50 bg-linear-to-br from-white/85 via-white/60 to-[#00A8E8]/10 p-4 shadow-[inset_0_1px_0_rgba(255,255,255,0.75)]">
              <div className="flex items-start justify-between gap-3">
                <div className="flex items-center gap-3">
                  <div className="flex h-11 w-11 shrink-0 items-center justify-center rounded-2xl bg-slate-900 text-white shadow-lg shadow-slate-900/15">
                    <svg
                      className="h-5 w-5"
                      viewBox="0 0 24 24"
                      fill="none"
                      stroke="currentColor"
                      strokeWidth="2"
                      strokeLinecap="round"
                      strokeLinejoin="round"
                    >
                      <path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z" />
                    </svg>
                  </div>
                  <div className="min-w-0">
                    <h2 className="text-sm font-semibold tracking-tight text-foreground">
                      History
                    </h2>
                    <p className="mt-1 text-xs leading-5 text-muted-foreground">
                      Select a context to continue your previous conversation.
                    </p>
                  </div>
                </div>

                <div className="rounded-full border border-[#00A8E8]/30 bg-white/90 px-2.5 py-1 text-[11px] font-semibold text-[#007EA7] shadow-sm">
                  {sessions.length}
                </div>
              </div>

              <button
                type="button"
                onClick={handleStartNewChat}
                disabled={sending}
                className="mt-4 inline-flex w-full items-center justify-center gap-2 rounded-2xl border border-[#00A8E8]/30 bg-white/85 px-3 py-2.5 text-sm font-medium text-[#007EA7] shadow-sm transition-all duration-200 hover:border-[#00A8E8]/50 hover:bg-[#00A8E8]/10 disabled:cursor-not-allowed disabled:opacity-60"
              >
                <svg
                  className="h-4 w-4"
                  viewBox="0 0 24 24"
                  fill="none"
                  stroke="currentColor"
                  strokeWidth="2"
                  strokeLinecap="round"
                  strokeLinejoin="round"
                >
                  <path d="M12 5v14" />
                  <path d="M5 12h14" />
                </svg>
                New chat
              </button>
            </div>

            <div className="mt-4 flex items-center justify-between px-1">
              <p className="text-[11px] font-medium uppercase tracking-[0.18em] text-muted-foreground/80">
                Recent
              </p>
              {activeSessionId && (
                <p className="text-[11px] text-muted-foreground">
                  1 item selected
                </p>
              )}
            </div>

            <div className="mt-3 flex-1 min-h-0 overflow-y-auto pr-1">
              {sessionsLoading ? (
                <div className="space-y-3">
                  {[1, 2, 3].map((item) => (
                    <div
                      key={item}
                      className="rounded-2xl border border-white/50 bg-white/70 p-3"
                    >
                      <div className="mb-3 flex items-center justify-between">
                        <div className="h-5 w-16 rounded-full skeleton" />
                        <div className="h-5 w-10 rounded-full skeleton" />
                      </div>
                      <div className="space-y-2">
                        <div className="h-4 w-4/5 rounded skeleton" />
                        <div className="h-3 w-3/5 rounded skeleton" />
                      </div>
                    </div>
                  ))}
                </div>
              ) : sessions.length === 0 ? (
                <div className="flex h-full min-h-48 flex-col items-center justify-center rounded-2xl border border-dashed border-[#00A8E8]/30 bg-white/55 px-5 text-center">
                  <div className="flex h-12 w-12 items-center justify-center rounded-2xl bg-[#00A8E8]/10 text-[#007EA7]">
                    <svg
                      className="h-5 w-5"
                      viewBox="0 0 24 24"
                      fill="none"
                      stroke="currentColor"
                      strokeWidth="2"
                      strokeLinecap="round"
                      strokeLinejoin="round"
                    >
                      <path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z" />
                    </svg>
                  </div>
                  <p className="mt-4 text-sm font-medium text-foreground">
                    No conversation history
                  </p>
                  <p className="mt-1 text-xs leading-5 text-muted-foreground">
                    Start your first message and your conversation history will appear here.
                  </p>
                </div>
              ) : (
                <div className="space-y-2.5">
                  {sessions.map((session) => {
                    const isActive = activeSessionId === session.id
                    return (
                      <div key={session.id} className="group relative w-full">
                        <button
                          type="button"
                          onClick={() => void handleSelectSession(session)}
                          className={`w-full cursor-pointer overflow-hidden rounded-2xl border px-3.5 py-3 pr-11 text-left transition-all duration-200 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary/30 ${
                            isActive
                              ? 'border-[#00A8E8]/40 bg-linear-to-br from-white via-[#00A8E8]/10 to-[#007EA7]/10 text-[#00171F] shadow-[0_16px_30px_rgba(0,168,232,0.14)] ring-1 ring-[#00A8E8]/30'
                              : 'border-white/60 bg-white/78 text-foreground shadow-[0_8px_24px_rgba(15,23,42,0.06)] hover:-translate-y-0.5 hover:border-[#00A8E8]/30 hover:bg-white hover:shadow-[0_14px_28px_rgba(0,168,232,0.12)]'
                          }`}
                        >
                        {isActive && (
                          <div
                            className="pointer-events-none absolute top-0 right-0 h-24 w-24 translate-x-1/4 -translate-y-1/4 rounded-full bg-[#00A8E8]/20 blur-2xl"
                            aria-hidden
                          />
                        )}

                        <div
                          className={`pointer-events-none absolute inset-y-3 left-0 w-1 rounded-r-full transition-colors ${
                            isActive
                              ? 'bg-[#00A8E8]'
                              : 'bg-[#00A8E8]/0 group-hover:bg-[#00A8E8]/50'
                          }`}
                          aria-hidden
                        />

                        <div className="flex items-start gap-3 pl-2">
                          <div className="min-w-0 flex-1">
                            <div className="line-clamp-2 text-sm font-semibold leading-5">
                              {session.title || 'Untitled session'}
                            </div>

                            <div
                              className={`mt-2 inline-flex items-center gap-2 text-[11px] ${
                                isActive
                                  ? 'text-slate-600'
                                  : 'text-muted-foreground'
                              }`}
                            >
                              <span
                                className={`h-2 w-2 rounded-full ${
                                  isActive ? 'bg-[#00A8E8]' : 'bg-[#007EA7]/60'
                                }`}
                              />
                              <span>{formatSessionTimestamp(session.created_at)}</span>
                              <span aria-hidden>•</span>
                              <span>{isActive ? 'Current' : 'Click to continue'}</span>
                            </div>
                          </div>

                          <svg
                            className={`mt-0.5 h-4 w-4 shrink-0 transition-transform duration-200 ${
                              isActive
                                ? 'translate-x-0 text-[#00A8E8]'
                                : 'text-muted-foreground group-hover:translate-x-0.5 group-hover:text-[#00A8E8]'
                            }`}
                            viewBox="0 0 24 24"
                            fill="none"
                            stroke="currentColor"
                            strokeWidth="2"
                            strokeLinecap="round"
                            strokeLinejoin="round"
                          >
                            <path d="M5 12h14" />
                            <path d="m12 5 7 7-7 7" />
                          </svg>
                        </div>
                        </button>
                        <button
                          type="button"
                          aria-label="Delete conversation"
                          onClick={() => requestDeleteSession(session)}
                          disabled={deletingSessionId != null}
                          className={`absolute top-3 right-3 z-10 rounded-lg p-1 transition-all duration-200 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary/30 ${
                            isActive
                              ? 'text-red-500 hover:bg-red-50'
                              : 'text-muted-foreground opacity-0 group-hover:opacity-100 hover:text-red-500 hover:bg-red-50'
                          } ${deletingSessionId === session.id ? 'cursor-not-allowed opacity-40' : ''} ${deletingSessionId != null && deletingSessionId !== session.id ? 'pointer-events-none opacity-0' : ''}`}
                        >
                          {deletingSessionId === session.id ? (
                            <svg
                              className="h-3.5 w-3.5 animate-spin"
                              viewBox="0 0 24 24"
                              fill="none"
                              aria-hidden
                            >
                              <circle
                                className="opacity-25"
                                cx="12"
                                cy="12"
                                r="10"
                                stroke="currentColor"
                                strokeWidth="4"
                              />
                              <path
                                className="opacity-75"
                                fill="currentColor"
                                d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4z"
                              />
                            </svg>
                          ) : (
                            <svg
                              className="h-3.5 w-3.5"
                              viewBox="0 0 24 24"
                              fill="none"
                              stroke="currentColor"
                              strokeWidth="2"
                              strokeLinecap="round"
                              strokeLinejoin="round"
                              aria-hidden
                            >
                              <path d="M3 6h18" />
                              <path d="M19 6v14c0 1-1 2-2 2H7c-1 0-2-1-2-2V6" />
                              <path d="M8 6V4c0-1 1-2 2-2h4c1 0 2 1 2 2v2" />
                            </svg>
                          )}
                        </button>
                      </div>
                    )
                  })}
                </div>
              )}
            </div>
          </aside>

          <div className="glass-card flex-1 min-h-0 flex flex-col overflow-hidden">
            <div
              ref={messageListRef}
              className="flex-1 min-h-0 overflow-y-auto overscroll-contain p-4 space-y-4"
              onWheelCapture={handleChatPanelWheel}
            >
              {loadingHistory ? (
                <p className="text-xs text-muted-foreground">Loading conversation...</p>
              ) : (
              messages.map((msg) => {
                const isUserMessage = msg.role === 'user'
                const isStreamingEmpty =
                  msg.role === 'assistant' &&
                  sending &&
                  msg.content === '' &&
                  messages[messages.length - 1]?.id === msg.id
                return (
                  <div
                    key={msg.id}
                    className={`flex ${isUserMessage ? 'justify-end' : 'justify-start'}`}
                  >
                    <div
                      className={`max-w-[85%] rounded-2xl px-4 py-3 ${
                        isUserMessage
                          ? 'rounded-br-md border border-[#00A8E8]/15 bg-white/92 text-foreground shadow-[0_8px_20px_rgba(15,23,42,0.05)]'
                          : 'rounded-bl-md border border-border bg-muted/80 text-foreground'
                      }`}
                    >
                      <span
                        className={`mb-1 block text-xs font-medium ${
                          isUserMessage ? 'text-slate-500' : 'text-muted-foreground'
                        }`}
                      >
                        {isUserMessage ? 'You' : 'Assistant'}
                      </span>
                      {isStreamingEmpty ? (
                        <div className="flex gap-1.5">
                          <span className="h-2 w-2 rounded-full bg-muted-foreground/60 animate-bounce [animation-delay:-0.3s]" />
                          <span className="h-2 w-2 rounded-full bg-muted-foreground/60 animate-bounce [animation-delay:-0.15s]" />
                          <span className="h-2 w-2 rounded-full bg-muted-foreground/60 animate-bounce" />
                        </div>
                      ) : (
                        <p
                          className={`text-sm leading-relaxed whitespace-pre-wrap break-words ${
                            isUserMessage ? 'text-foreground' : 'text-foreground'
                          }`}
                        >
                          {msg.content || '\u00A0'}
                        </p>
                      )}
                    </div>
                  </div>
                )
              })
              )}
            </div>

            <form
              onSubmit={handleSubmit}
              className="p-4 border-t border-border bg-background/50"
            >
              <div className="flex gap-2 items-end">
                <textarea
                  value={input}
                  onChange={(e) => setInput(e.target.value)}
                  onKeyDown={(e) => {
                    if (e.key === 'Enter' && !e.shiftKey) {
                      e.preventDefault()
                      ;(e.target as HTMLTextAreaElement).form?.requestSubmit()
                    }
                  }}
                  placeholder="Type a message... (Enter to send, Shift+Enter to new line)"
                  disabled={sending}
                  rows={1}
                  className="glass-input flex-1 min-h-[44px] max-h-32 resize-y rounded-xl px-4 py-3 text-sm text-foreground placeholder:text-muted-foreground focus:outline-none disabled:opacity-60"
                />
                <button
                  type="submit"
                  disabled={sending || !input.trim()}
                  className="rounded-xl px-5 py-3 text-sm font-semibold text-white shadow-lg transition-all duration-200 hover:opacity-90 disabled:opacity-50 disabled:cursor-not-allowed cursor-pointer"
                  style={{ backgroundColor: '#00A8E8' }}
                >
                  Send
                </button>
              </div>
            </form>
          </div>
        </div>
      </div>

      <dialog
        ref={deleteDialogRef}
        onCancel={cancelDeleteSession}
        onClose={cancelDeleteSession}
        className="w-[min(100%,24rem)] rounded-2xl border border-white/60 bg-white/95 p-0 text-foreground shadow-[0_24px_48px_rgba(15,23,42,0.18)] backdrop:bg-[#00171F]/40 backdrop:backdrop-blur-sm open:animate-in"
      >
        <form
          onSubmit={(e) => {
            e.preventDefault()
            void confirmDeleteSession()
          }}
          className="p-6"
        >
          <h2 className="text-lg font-semibold text-foreground">Delete conversation?</h2>
          <p className="mt-2 text-sm leading-6 text-muted-foreground">
            {sessionPendingDelete?.title
              ? `"${sessionPendingDelete.title}" will be permanently removed.`
              : 'This conversation will be permanently removed.'}
          </p>
          <div className="mt-6 flex justify-end gap-2">
            <Button type="button" variant="outline" onClick={cancelDeleteSession}>
              Cancel
            </Button>
            <Button type="submit" variant="destructive">
              Delete
            </Button>
          </div>
        </form>
      </dialog>
    </section>
  )
}
