import { useState, useRef, useEffect, useCallback } from 'react'
import { useNavigate } from 'react-router-dom'
import {
  chatStreamAPI,
  type ChatLocation,
  type ChatSession,
  type ChatMessageDTO,
  getChatSessionsAPI,
  getChatSessionMessagesAPI,
  deleteChatSessionAPI,
} from '@/api/chat'
import { getAccessToken } from '@/api/token'
import { getMeAPI } from '@/api/user'
import { ChatMessageContent } from '@/components/chat/ChatMessageContent'
import { ChatThinkingIndicator } from '@/components/chat/ChatThinkingIndicator'
import { Button } from '@/components/ui/button'
import { needsCurrentLocation } from '@/lib/chat-location-intent'
import { toast } from 'sonner'

type Role = 'user' | 'assistant'

interface Message {
  id: string
  role: Role
  content: string
  createdAt: Date
}

interface SuggestionPrompt {
  label: string
  description: string
  prompt: string
  icon: 'pin' | 'bike' | 'route' | 'weather'
}

const SUGGESTED_PROMPTS: SuggestionPrompt[] = [
  {
    label: 'Find the nearest bike station',
    description: 'Use my current location to find stations near me.',
    prompt: 'Find the nearest bike station to my current location.',
    icon: 'pin',
  },
  {
    label: 'Check bike availability',
    description: 'See how many bikes and docks are available right now.',
    prompt: 'How many bikes are available right now?',
    icon: 'bike',
  },
  {
    label: 'Plan a cycling route',
    description: 'Get directions between two places in Dublin.',
    prompt: 'Plan a route from Trinity College to Phoenix Park.',
    icon: 'route',
  },
  {
    label: "Today's cycling weather",
    description: 'Check the weather before you head out.',
    prompt: "What's the weather like for cycling in Dublin today?",
    icon: 'weather',
  },
]

const WELCOME_MESSAGE: Message = {
  id: 'welcome',
  role: 'assistant',
  content:
    "Hi! I'm your Dublin Bikes assistant. Ask me about bike sharing, stations, or sustainable mobility—or just say hello.",
  createdAt: new Date(),
}

function SuggestionIcon({ name }: { name: 'pin' | 'bike' | 'route' | 'weather' }) {
  const common = {
    className: 'h-4 w-4 shrink-0',
    viewBox: '0 0 24 24',
    fill: 'none',
    stroke: 'currentColor',
    strokeWidth: 2,
    strokeLinecap: 'round' as const,
    strokeLinejoin: 'round' as const,
  }
  if (name === 'pin') {
    return (
      <svg {...common}>
        <path d="M20 10c0 6-8 12-8 12s-8-6-8-12a8 8 0 0 1 16 0Z" />
        <circle cx="12" cy="10" r="3" />
      </svg>
    )
  }
  if (name === 'bike') {
    return (
      <svg {...common}>
        <circle cx="5.5" cy="17.5" r="3.5" />
        <circle cx="18.5" cy="17.5" r="3.5" />
        <path d="M15 6a1 1 0 1 0 0-2 1 1 0 0 0 0 2Zm-3 11.5V14l-3-3 4-3 2 3h2" />
      </svg>
    )
  }
  if (name === 'route') {
    return (
      <svg {...common}>
        <circle cx="6" cy="19" r="3" />
        <path d="M9 19h8.5a3.5 3.5 0 0 0 0-7h-11a3.5 3.5 0 0 1 0-7H15" />
        <circle cx="18" cy="5" r="3" />
      </svg>
    )
  }
  return (
    <svg {...common}>
      <path d="M17.5 19a4.5 4.5 0 0 0 0-9 7 7 0 1 0-13 2.5" />
      <path d="M8 19h.01M8 15h.01M12 21h.01M12 17h.01M16 19h.01M16 15h.01" />
    </svg>
  )
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

function getCurrentChatLocation(signal: AbortSignal): Promise<ChatLocation | undefined> {
  if (!navigator.geolocation) return Promise.resolve(undefined)

  return new Promise((resolve) => {
    navigator.geolocation.getCurrentPosition(
      (position) => {
        if (signal.aborted) {
          resolve(undefined)
          return
        }
        resolve({
          lat: position.coords.latitude,
          lng: position.coords.longitude,
          accuracy_m: position.coords.accuracy,
        })
      },
      () => resolve(undefined),
      { enableHighAccuracy: true, timeout: 10000, maximumAge: 60000 }
    )
  })
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

function formatMessageTime(date: Date): string {
  if (!(date instanceof Date) || Number.isNaN(date.getTime())) return ''
  return date.toLocaleTimeString('en-US', { hour: 'numeric', minute: '2-digit' })
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

function getLastUserTextBefore(messages: Message[], fromIndex: number): string {
  for (let i = fromIndex - 1; i >= 0; i -= 1) {
    if (messages[i]?.role === 'user' && messages[i]?.content.trim()) {
      return messages[i]?.content ?? ''
    }
  }
  return ''
}

function MessageAvatar({ role }: { role: Role }) {
  if (role === 'user') {
    return (
      <div
        className="flex h-8 w-8 shrink-0 items-center justify-center rounded-xl bg-slate-900 text-white shadow-md shadow-slate-900/15 sm:h-9 sm:w-9"
        aria-hidden
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
          <path d="M19 21v-2a4 4 0 0 0-4-4H9a4 4 0 0 0-4 4v2" />
          <circle cx="12" cy="7" r="4" />
        </svg>
      </div>
    )
  }
  return (
    <div
      className="flex h-8 w-8 shrink-0 items-center justify-center rounded-xl bg-gradient-to-br from-[#00A8E8] to-[#007EA7] text-white shadow-md shadow-[#00A8E8]/25 ring-1 ring-white/40 sm:h-9 sm:w-9"
      aria-hidden
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
        <path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z" />
      </svg>
    </div>
  )
}

export default function Chat() {
  const navigate = useNavigate()
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
  const [showScrollToBottom, setShowScrollToBottom] = useState(false)
  const [copiedMessageId, setCopiedMessageId] = useState<string | null>(null)
  const [mobileHistoryOpen, setMobileHistoryOpen] = useState(false)
  const deleteDialogRef = useRef<HTMLDialogElement>(null)
  const abortRef = useRef<AbortController | null>(null)
  const messageListRef = useRef<HTMLDivElement>(null)
  const textareaRef = useRef<HTMLTextAreaElement>(null)
  const chatIdSuffixRef = useRef<string>('')
  const chatIdInitPromiseRef = useRef<Promise<string> | null>(null)
  const historyRequestIdRef = useRef(0)
  const pendingScrollToBottomRef = useRef(false)
  const stickToBottomRef = useRef(true)
  const submitLockRef = useRef(false)
  const sendingRef = useRef(false)
  const isMountedRef = useRef(true)
  const copyResetTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null)

  const scrollToBottom = useCallback(() => {
    requestAnimationFrame(() => {
      requestAnimationFrame(() => {
        const el = messageListRef.current
        if (el) {
          el.scrollTop = el.scrollHeight
        }
      })
    })
  }, [])

  const resizeTextarea = useCallback(() => {
    const el = textareaRef.current
    if (!el) return
    el.style.height = 'auto'
    el.style.height = `${Math.min(el.scrollHeight, 160)}px`
  }, [])

  const handleListScroll = useCallback(() => {
    const el = messageListRef.current
    if (!el) return
    const distanceFromBottom = el.scrollHeight - el.scrollTop - el.clientHeight
    const atBottom = distanceFromBottom < 80
    stickToBottomRef.current = atBottom
    setShowScrollToBottom(!atBottom && el.scrollHeight > el.clientHeight + 80)
  }, [])

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
      sendingRef.current = false
      setSending(false)
      if (copyResetTimerRef.current) {
        clearTimeout(copyResetTimerRef.current)
        copyResetTimerRef.current = null
      }
    }
  }, [])

  // Focus the composer on desktop once the page settles.
  useEffect(() => {
    if (!window.matchMedia('(min-width: 768px)').matches) return
    const timer = window.setTimeout(() => {
      textareaRef.current?.focus()
    }, 400)
    return () => window.clearTimeout(timer)
  }, [])

  useEffect(() => {
    if (!pendingScrollToBottomRef.current || loadingHistory) return
    pendingScrollToBottomRef.current = false
    stickToBottomRef.current = true
    scrollToBottom()
  }, [messages, loadingHistory, scrollToBottom])

  const releaseSubmitLock = () => {
    submitLockRef.current = false
    sendingRef.current = false
    abortRef.current = null
  }

  const finishSending = () => {
    setSending(false)
    releaseSubmitLock()
  }

  const handleSelectSession = async (session: ChatSession) => {
    abortRef.current?.abort()
    setSending(false)
    releaseSubmitLock()
    setMobileHistoryOpen(false)

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
    setMobileHistoryOpen(false)
    stickToBottomRef.current = true
    setTimeout(() => {
      const el = messageListRef.current
      if (el) el.scrollTop = 0
      resizeTextarea()
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

  const handleStopGeneration = () => {
    abortRef.current?.abort()
    // Drop a trailing empty assistant bubble if the user stopped before any text arrived.
    setMessages((prev) => {
      const last = prev[prev.length - 1]
      if (last && last.role === 'assistant' && last.content.trim() === '') {
        return prev.slice(0, -1)
      }
      return prev
    })
    finishSending()
  }

  const handleCopyMessage = async (message: Message) => {
    if (!message.content) return
    try {
      await navigator.clipboard.writeText(message.content)
      setCopiedMessageId(message.id)
      if (copyResetTimerRef.current) clearTimeout(copyResetTimerRef.current)
      copyResetTimerRef.current = setTimeout(() => {
        if (isMountedRef.current) setCopiedMessageId(null)
      }, 2000)
    } catch {
      toast.error('Could not copy to clipboard')
    }
  }

  const sendMessage = async (rawText: string) => {
    const text = rawText.trim()
    if (!text || sendingRef.current || submitLockRef.current) return

    submitLockRef.current = true
    sendingRef.current = true
    setSending(true)
    stickToBottomRef.current = true
    const controller = new AbortController()
    abortRef.current = controller

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

    // Remove the welcome bubble and empty-state hint from the visible
    // thread once a real conversation starts; the hero greeting becomes
    // the welcome UI.
    setMessages((prev) => [
      ...prev.filter((m) => m.id !== 'welcome' && m.id !== 'history-empty'),
      userMessage,
      assistantMessage,
    ])
    setInput('')
    requestAnimationFrame(resizeTextarea)

    const rollbackOptimisticMessages = () => {
      setMessages((prev) =>
        prev.filter((m) => m.id !== userMessage.id && m.id !== assistantMessage.id)
      )
    }

    let resolvedChatId: string
    try {
      resolvedChatId = await ensureChatId()
    } catch {
      rollbackOptimisticMessages()
      finishSending()
      return
    }
    if (controller.signal.aborted || !isMountedRef.current) {
      rollbackOptimisticMessages()
      finishSending()
      return
    }

    try {
      const location = needsCurrentLocation(text)
        ? await getCurrentChatLocation(controller.signal)
        : undefined
      if (controller.signal.aborted || !isMountedRef.current) {
        rollbackOptimisticMessages()
        finishSending()
        return
      }

      await chatStreamAPI({
        chat_id: resolvedChatId,
        message: text,
        location,
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
          if (stickToBottomRef.current && document.visibilityState === 'visible') {
            requestAnimationFrame(() => {
              const el = messageListRef.current
              if (el) el.scrollTop = el.scrollHeight
            })
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
              m.id === assistantId ? { ...m, content: `[Request failed] ${err.message}` } : m
            )
          )
          toast.error(err.message)
        },
      })
    } catch {
      // Stream failures are surfaced via the onError callback above.
      // chatStreamAPI reports every rejection through onError before it
      // rejects (and consumes the internal retry signal itself), so handling
      // the error again here would duplicate the toast and state update.
    } finally {
      finishSending()
    }
  }

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault()
    void sendMessage(input)
  }

  const handleSuggestionClick = (prompt: string) => {
    void sendMessage(prompt)
  }

  const handleRetryFailed = (failedMessageId: string, question: string) => {
    if (sendingRef.current || submitLockRef.current) return
    setMessages((prev) => prev.filter((m) => m.id !== failedMessageId))
    void sendMessage(question)
  }

  const isFailedMessage = (content: string): boolean =>
    content.startsWith('[Request failed]')
  const isAuthFailureDetail = (detail: string): boolean =>
    /please sign in|session expired.*sign in/i.test(detail)

  const renderSessionItems = () => {
    if (sessionsLoading) {
      return (
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
      )
    }

    if (sessions.length === 0) {
      return (
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
          <p className="mt-4 text-sm font-medium text-foreground">No conversation history</p>
          <p className="mt-1 text-xs leading-5 text-muted-foreground">
            Start your first message and your conversation history will appear here.
          </p>
        </div>
      )
    }

    return (
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
                    isActive ? 'bg-[#00A8E8]' : 'bg-[#00A8E8]/0 group-hover:bg-[#00A8E8]/50'
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
                        isActive ? 'text-slate-600' : 'text-muted-foreground'
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
                title="Delete conversation"
                className={`absolute top-3 right-3 z-10 rounded-lg p-1 transition-all duration-200 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary/30 ${
                  isActive
                    ? 'text-red-500 hover:bg-red-50'
                    : 'text-muted-foreground opacity-0 group-hover:opacity-100 hover:text-red-500 hover:bg-red-50'
                } ${deletingSessionId === session.id ? 'cursor-not-allowed opacity-40' : ''} ${
                  deletingSessionId != null && deletingSessionId !== session.id
                    ? 'pointer-events-none opacity-0'
                    : ''
                }`}
              >
                {deletingSessionId === session.id ? (
                  <svg className="h-3.5 w-3.5 animate-spin" viewBox="0 0 24 24" fill="none" aria-hidden>
                    <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4" />
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
    )
  }

  const isWelcomeState =
    !loadingHistory && !sending && messages.length === 1 && messages[0]?.id === 'welcome'

  return (
    <section className="h-screen w-full pt-24 pb-8 flex flex-col overflow-hidden">
      <div className="fixed inset-0 -z-10 overflow-hidden">
        <div className="absolute -top-40 -right-40 h-80 w-80 rounded-full bg-primary/10 blur-3xl" />
        <div className="absolute -bottom-40 -left-40 h-80 w-80 rounded-full bg-secondary/10 blur-3xl" />
      </div>

      <div className="mx-auto w-full max-w-5xl flex-1 min-h-0 flex flex-col px-4">
        <div className="mb-4 shrink-0">
          <div className="flex items-center justify-between gap-4">
            <div>
              <h1 className="text-2xl font-bold text-foreground">AI Chat</h1>
              <p className="mt-1 text-sm text-muted-foreground">
                Ask about bike sharing, stations, or anything Dublin Bikes related.
              </p>
            </div>

            <div className="flex shrink-0 items-center gap-2">
              <span className="hidden items-center gap-1.5 rounded-full border border-green-500/20 bg-green-500/10 px-3 py-1.5 text-xs font-medium text-green-700 sm:inline-flex">
                <span className="relative flex h-2 w-2" aria-hidden>
                  <span className="absolute inline-flex h-full w-full animate-ping rounded-full bg-green-500 opacity-60" />
                  <span className="relative inline-flex h-2 w-2 rounded-full bg-green-500" />
                </span>
                Assistant online
              </span>

              <button
                type="button"
                onClick={() => setMobileHistoryOpen(true)}
                disabled={sending}
                aria-label="Open conversation history"
                className="inline-flex items-center gap-2 rounded-xl border border-[#00A8E8]/30 bg-white/80 px-3.5 py-2 text-sm font-medium text-[#007EA7] shadow-sm transition-all duration-200 hover:border-[#00A8E8]/50 hover:bg-[#00A8E8]/10 disabled:cursor-not-allowed disabled:opacity-60 md:hidden"
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
                  <path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z" />
                </svg>
                History
              </button>
              <button
                type="button"
                onClick={handleStartNewChat}
                disabled={sending}
                aria-label="Start a new chat"
                className="inline-flex items-center gap-2 rounded-xl border border-[#00A8E8]/30 bg-white/80 px-3.5 py-2 text-sm font-medium text-[#007EA7] shadow-sm transition-all duration-200 hover:border-[#00A8E8]/50 hover:bg-[#00A8E8]/10 disabled:cursor-not-allowed disabled:opacity-60 md:hidden"
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
                New
              </button>
            </div>
          </div>
        </div>

        <div className="flex-1 min-h-0 flex gap-4">
          {/* Desktop sidebar */}
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
                    <h2 className="text-sm font-semibold tracking-tight text-foreground">History</h2>
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
            </div>

            <div className="mt-3 flex-1 min-h-0 overflow-y-auto pr-1">{renderSessionItems()}</div>
          </aside>

          <div className="relative flex flex-1 min-h-0 flex-col overflow-hidden rounded-[28px] border border-white/45 bg-white/55 shadow-[0_20px_60px_rgba(0,52,89,0.12)] backdrop-blur-xl">
            <div
              ref={messageListRef}
              onScroll={handleListScroll}
              className="flex-1 min-h-0 overflow-y-auto overscroll-contain p-4 scroll-smooth sm:p-6"
            >
              {isWelcomeState ? (
                <div className="flex h-full min-h-full items-center justify-center">
                  <div className="w-full max-w-2xl py-6 text-center">
                    <div className="mx-auto flex h-16 w-16 items-center justify-center rounded-3xl bg-gradient-to-br from-[#00A8E8] to-[#007EA7] text-white shadow-xl shadow-[#00A8E8]/30 ring-4 ring-white/60">
                      <svg
                        className="h-8 w-8"
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

                    <h2 className="mt-6 text-xl font-bold tracking-tight text-foreground sm:text-2xl">
                      Hello! I&apos;m your Dublin Bikes Assistant
                    </h2>
                    <p className="mx-auto mt-3 max-w-lg text-sm leading-6 text-muted-foreground sm:text-base sm:leading-7">
                      Ask me about bike sharing, station availability, cycling routes, or the
                      weather in Dublin. Try one of these to get started:
                    </p>

                    <div className="mt-8 grid gap-3 text-left sm:grid-cols-2">
                      {SUGGESTED_PROMPTS.map((suggestion) => (
                        <button
                          key={suggestion.label}
                          type="button"
                          onClick={() => handleSuggestionClick(suggestion.prompt)}
                          disabled={sending}
                          className="group flex items-start gap-3.5 rounded-2xl border border-white/70 bg-white/85 p-4 text-left shadow-[0_8px_24px_rgba(15,23,42,0.06)] backdrop-blur transition-all duration-200 hover:-translate-y-0.5 hover:border-[#00A8E8]/40 hover:shadow-[0_16px_32px_rgba(0,168,232,0.16)] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary/40 disabled:cursor-not-allowed disabled:opacity-60"
                        >
                          <span className="flex h-10 w-10 shrink-0 items-center justify-center rounded-xl bg-gradient-to-br from-[#00A8E8]/15 to-[#007EA7]/15 text-[#007EA7] transition-colors duration-200 group-hover:from-[#00A8E8] group-hover:to-[#007EA7] group-hover:text-white">
                            <SuggestionIcon name={suggestion.icon} />
                          </span>
                          <span className="min-w-0 flex-1">
                            <span className="block text-sm font-semibold leading-5 text-foreground">
                              {suggestion.label}
                            </span>
                            <span className="mt-1 block text-xs leading-5 text-muted-foreground">
                              {suggestion.description}
                            </span>
                          </span>
                          <svg
                            className="h-4 w-4 shrink-0 self-center text-muted-foreground/50 transition-all duration-200 group-hover:translate-x-0.5 group-hover:text-[#00A8E8]"
                            viewBox="0 0 24 24"
                            fill="none"
                            stroke="currentColor"
                            strokeWidth="2"
                            strokeLinecap="round"
                            strokeLinejoin="round"
                            aria-hidden
                          >
                            <path d="M5 12h14" />
                            <path d="m12 5 7 7-7 7" />
                          </svg>
                        </button>
                      ))}
                    </div>
                  </div>
                </div>
              ) : loadingHistory ? (
                <div className="space-y-5">
                  {[0, 1, 2].map((i) => (
                    <div
                      key={i}
                      className={`flex ${i % 2 === 0 ? 'justify-start' : 'justify-end'}`}
                    >
                      <div
                        className={`max-w-[85%] rounded-2xl border border-white/70 bg-white/80 px-4 py-3 ${
                          i % 2 === 0 ? 'w-3/4' : 'w-1/2'
                        }`}
                      >
                        <div className="mb-2 h-3 w-12 rounded skeleton" />
                        <div className="space-y-2">
                          <div className="h-3.5 w-full rounded skeleton" />
                          <div className="h-3.5 w-4/5 rounded skeleton" />
                        </div>
                      </div>
                    </div>
                  ))}
                </div>
              ) : (
                <>
                  {messages.map((msg, index) => {
                    const isUserMessage = msg.role === 'user'
                    const isStreamingEmpty =
                      msg.role === 'assistant' &&
                      sending &&
                      msg.content === '' &&
                      messages[messages.length - 1]?.id === msg.id
                    const isFailed = !isUserMessage && isFailedMessage(msg.content)
                    const time = formatMessageTime(msg.createdAt)
                    const failedDetail = isFailed
                      ? msg.content.replace(/^\[Request failed\]\s*/, '').trim()
                      : ''
                    const failedUserText = isFailed
                      ? getLastUserTextBefore(messages, index)
                      : ''
                    const failedAuth = isFailed && isAuthFailureDetail(failedDetail)

                    if (msg.id === 'history-empty') {
                      return (
                        <div
                          key={msg.id}
                          className="flex h-full min-h-56 flex-col items-center justify-center text-center"
                        >
                          <div className="flex h-14 w-14 items-center justify-center rounded-2xl bg-[#00A8E8]/10 text-[#007EA7]">
                            <svg
                              className="h-6 w-6"
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
                            This conversation is empty
                          </p>
                          <p className="mt-1 text-xs leading-5 text-muted-foreground">
                            Ask a question below to start the conversation.
                          </p>
                        </div>
                      )
                    }

                    if (isUserMessage) {
                      return (
                        <div key={msg.id} className="flex justify-end gap-3">
                          <div className="group/msg mt-1 flex max-w-[85%] flex-col items-end sm:max-w-[75%]">
                            <div className="mb-1 flex items-center gap-2 pr-1">
                              <span className="text-xs font-medium text-slate-500">You</span>
                              {time && (
                                <span className="text-[10px] text-muted-foreground/70">
                                  {time}
                                </span>
                              )}
                            </div>
                            <div className="rounded-2xl rounded-br-md bg-gradient-to-br from-[#00A8E8] to-[#007EA7] px-4 py-3 text-white shadow-[0_8px_20px_rgba(0,168,232,0.24)]">
                              <p className="text-sm leading-relaxed whitespace-pre-wrap break-words">
                                {msg.content || '\u00A0'}
                              </p>
                            </div>
                          </div>
                          <MessageAvatar role="user" />
                        </div>
                      )
                    }

                    return (
                      <div key={msg.id} className="flex justify-start gap-3">
                        <MessageAvatar role="assistant" />
                        <div className="group/msg mt-1 flex min-w-0 max-w-[85%] flex-col items-start sm:max-w-[75%]">
                          <div className="mb-1 flex items-center gap-2 pl-1">
                            <span className="text-xs font-medium text-muted-foreground">
                              Assistant
                            </span>
                            {time && (
                              <span className="text-[10px] text-muted-foreground/70">
                                {time}
                              </span>
                            )}
                          </div>

                          {isStreamingEmpty ? (
                            <div className="rounded-2xl rounded-bl-md border border-white/70 bg-white/85 px-4 py-3 shadow-[0_6px_20px_rgba(15,23,42,0.05)]">
                              <ChatThinkingIndicator />
                            </div>
                          ) : isFailed ? (
                            <div className="w-full rounded-2xl rounded-bl-md border border-red-200 bg-red-50/90 px-4 py-3.5 shadow-[0_6px_20px_rgba(239,68,68,0.08)]">
                              <div className="flex items-center gap-2">
                                <span className="flex h-7 w-7 shrink-0 items-center justify-center rounded-lg bg-red-100 text-red-500">
                                  <svg
                                    className="h-4 w-4"
                                    viewBox="0 0 24 24"
                                    fill="none"
                                    stroke="currentColor"
                                    strokeWidth="2"
                                    strokeLinecap="round"
                                    strokeLinejoin="round"
                                    aria-hidden
                                  >
                                    <circle cx="12" cy="12" r="10" />
                                    <path d="M12 8v4" />
                                    <path d="M12 16h.01" />
                                  </svg>
                                </span>
                                <p className="text-sm font-semibold text-red-600">
                                  Sorry, something went wrong
                                </p>
                              </div>
                              {failedDetail && (
                                <p className="mt-1.5 pl-9 text-xs leading-5 text-red-500/90">
                                  {failedDetail}
                                </p>
                              )}
                              {failedUserText && (
                                <div className="mt-3 flex flex-wrap items-center gap-2 pl-9">
                                  <button
                                    type="button"
                                    onClick={() =>
                                      handleRetryFailed(msg.id, failedUserText)
                                    }
                                    className="inline-flex items-center gap-1.5 rounded-lg bg-red-500 px-3 py-1.5 text-xs font-semibold text-white shadow-sm transition-colors duration-200 hover:bg-red-600 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-red-400/50"
                                  >
                                    <svg
                                      className="h-3 w-3"
                                      viewBox="0 0 24 24"
                                      fill="none"
                                      stroke="currentColor"
                                      strokeWidth="2.5"
                                      strokeLinecap="round"
                                      strokeLinejoin="round"
                                      aria-hidden
                                    >
                                      <path d="M3 12a9 9 0 1 0 9-9 9.75 9.75 0 0 0-6.74 2.74L3 8" />
                                      <path d="M3 3v5h5" />
                                    </svg>
                                    Try again
                                  </button>
                                  {failedAuth && (
                                    <button
                                      type="button"
                                      onClick={() => navigate('/login')}
                                      className="inline-flex items-center gap-1.5 rounded-lg border border-red-300 bg-white px-3 py-1.5 text-xs font-semibold text-red-600 shadow-sm transition-colors duration-200 hover:bg-red-50 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-red-400/40"
                                    >
                                      <svg
                                        className="h-3 w-3"
                                        viewBox="0 0 24 24"
                                        fill="none"
                                        stroke="currentColor"
                                        strokeWidth="2"
                                        strokeLinecap="round"
                                        strokeLinejoin="round"
                                        aria-hidden
                                      >
                                        <path d="M15 3h4a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2h-4" />
                                        <path d="m10 17 5-5-5-5" />
                                        <path d="M15 12H3" />
                                      </svg>
                                      Sign in to chat
                                    </button>
                                  )}
                                </div>
                              )}
                            </div>
                          ) : (
                            <div className="rounded-2xl rounded-bl-md border border-white/70 bg-white/85 px-4 py-3 shadow-[0_6px_20px_rgba(15,23,42,0.05)]">
                              <ChatMessageContent content={msg.content} />
                            </div>
                          )}

                          {!isUserMessage &&
                            msg.content.trim() &&
                            (
                              <div className="mt-1.5 flex items-center pl-1 opacity-100 transition-opacity duration-150 md:opacity-0 md:group-hover/msg:opacity-100 md:focus-within:opacity-100">
                                <button
                                  type="button"
                                  onClick={() => void handleCopyMessage(msg)}
                                  className="inline-flex items-center gap-1 rounded-md px-1.5 py-1 text-[11px] font-medium text-muted-foreground transition-colors hover:bg-muted hover:text-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary/30"
                                  aria-label="Copy message"
                                >
                                  {copiedMessageId === msg.id ? (
                                    <>
                                      <svg
                                        className="h-3.5 w-3.5"
                                        viewBox="0 0 24 24"
                                        fill="none"
                                        stroke="currentColor"
                                        strokeWidth="2"
                                        strokeLinecap="round"
                                        strokeLinejoin="round"
                                      >
                                        <path d="M20 6 9 17l-5-5" />
                                      </svg>
                                      Copied
                                    </>
                                  ) : (
                                    <>
                                      <svg
                                        className="h-3.5 w-3.5"
                                        viewBox="0 0 24 24"
                                        fill="none"
                                        stroke="currentColor"
                                        strokeWidth="2"
                                        strokeLinecap="round"
                                        strokeLinejoin="round"
                                      >
                                        <rect width="14" height="14" x="8" y="8" rx="2" ry="2" />
                                        <path d="M4 16c-1.1 0-2-.9-2-2V4c0-1.1.9-2 2-2h10c1.1 0 2 .9 2 2" />
                                      </svg>
                                      Copy
                                    </>
                                  )}
                                </button>
                              </div>
                            )}
                        </div>
                      </div>
                    )
                  })}
                </>
              )}
            </div>

            {/* Scroll-to-bottom button */}
            {showScrollToBottom && !loadingHistory && (
              <button
                type="button"
                onClick={() => {
                  stickToBottomRef.current = true
                  setShowScrollToBottom(false)
                  scrollToBottom()
                }}
                aria-label="Scroll to latest message"
                className="absolute bottom-24 left-1/2 z-10 -translate-x-1/2 inline-flex h-9 w-9 items-center justify-center rounded-full border border-[#00A8E8]/30 bg-white/95 text-[#007EA7] shadow-lg shadow-[#003459]/15 backdrop-blur-md transition-all duration-200 hover:bg-white hover:scale-105 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary/40"
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
                  <path d="m19 12-7 7-7-7" />
                </svg>
              </button>
            )}

            <form onSubmit={handleSubmit} className="border-t border-border/70 bg-background/40 p-3 backdrop-blur-xl sm:p-4">
              <div className="mx-auto max-w-3xl">
                <div
                  className={`flex items-end gap-2 rounded-2xl border bg-white/90 p-2 shadow-[0_10px_30px_rgba(0,52,89,0.08)] transition-all duration-200 ${
                    sending
                      ? 'border-[#00A8E8]/30'
                      : 'border-[#00A8E8]/20 focus-within:border-[#00A8E8]/50 focus-within:ring-4 focus-within:ring-[#00A8E8]/10'
                  }`}
                >
                  <label htmlFor="chat-input" className="sr-only">
                    Type a message
                  </label>
                  <textarea
                    ref={textareaRef}
                    id="chat-input"
                    value={input}
                    onChange={(e) => {
                      setInput(e.target.value)
                      resizeTextarea()
                    }}
                    onKeyDown={(e) => {
                      // Ignore Enter while an IME composition (e.g. Chinese
                      // pinyin) is still in progress so Enter confirms the
                      // candidate instead of accidentally sending.
                      if (e.key === 'Enter' && !e.shiftKey && !e.nativeEvent.isComposing) {
                        e.preventDefault()
                        ;(e.target as HTMLTextAreaElement).form?.requestSubmit()
                      }
                    }}
                    placeholder="Ask about bikes, stations, routes, weather…"
                    disabled={sending}
                    rows={1}
                    className="flex-1 min-h-[44px] max-h-40 resize-none overflow-y-auto bg-transparent px-3 py-2.5 text-sm leading-relaxed text-foreground placeholder:text-muted-foreground focus:outline-none disabled:opacity-60"
                  />
                  {sending ? (
                    <button
                      type="button"
                      onClick={handleStopGeneration}
                      aria-label="Stop generating"
                      title="Stop generating"
                      className="inline-flex h-10 w-10 shrink-0 items-center justify-center rounded-xl bg-slate-900 text-white shadow-lg shadow-slate-900/20 transition-all duration-200 hover:bg-slate-700 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-slate-900/40"
                    >
                      <svg
                        className="h-4 w-4"
                        viewBox="0 0 24 24"
                        fill="currentColor"
                        aria-hidden
                      >
                        <rect x="6" y="6" width="12" height="12" rx="2" />
                      </svg>
                    </button>
                  ) : (
                    <button
                      type="submit"
                      disabled={!input.trim()}
                      aria-label="Send message"
                      title="Send message (Enter)"
                      className="inline-flex h-10 w-10 shrink-0 cursor-pointer items-center justify-center rounded-xl bg-gradient-to-br from-[#00A8E8] to-[#007EA7] text-white shadow-lg shadow-[#00A8E8]/30 transition-all duration-200 hover:scale-105 hover:shadow-[#00A8E8]/50 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[#00A8E8]/40 disabled:cursor-not-allowed disabled:scale-100 disabled:bg-none disabled:bg-[#00A8E8]/25 disabled:text-white/70 disabled:shadow-none"
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
                        <path d="m22 2-7 20-4-9-9-4Z" />
                        <path d="M22 2 11 13" />
                      </svg>
                    </button>
                  )}
                </div>

                {!sending && (
                  <p className="mt-2 hidden items-center gap-1.5 px-2 text-[11px] text-muted-foreground/70 sm:flex">
                    <svg
                      className="h-3 w-3"
                      viewBox="0 0 24 24"
                      fill="none"
                      stroke="currentColor"
                      strokeWidth="2"
                      strokeLinecap="round"
                      strokeLinejoin="round"
                      aria-hidden
                    >
                      <rect width="20" height="16" x="2" y="4" rx="2" />
                      <path d="M6 8h.01M10 8h.01M14 8h.01M18 8h.01M7 12h.01M11 12h.01M15 12h.01M18 12h.01M7 16h10" />
                    </svg>
                    Enter to send — Shift + Enter for a new line
                  </p>
                )}
              </div>
            </form>
          </div>
        </div>
      </div>

      {/* Mobile history drawer */}
      {mobileHistoryOpen && (
        <div className="fixed inset-0 z-50 md:hidden">
          <button
            type="button"
            aria-label="Close conversation history"
            onClick={() => setMobileHistoryOpen(false)}
            className="absolute inset-0 bg-[#00171F]/40 backdrop-blur-sm"
          />
          <div className="absolute inset-y-0 left-0 flex w-[min(85vw,20rem)] max-w-full flex-col bg-white/95 shadow-2xl">
            <div className="flex items-center justify-between border-b border-border p-4">
              <div className="flex items-center gap-2">
                <div className="flex h-9 w-9 items-center justify-center rounded-xl bg-slate-900 text-white">
                  <svg
                    className="h-4 w-4"
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
                <h2 className="text-sm font-semibold text-foreground">History</h2>
                <span className="rounded-full border border-[#00A8E8]/30 bg-white/90 px-2 py-0.5 text-[11px] font-semibold text-[#007EA7]">
                  {sessions.length}
                </span>
              </div>
              <button
                type="button"
                aria-label="Close"
                onClick={() => setMobileHistoryOpen(false)}
                className="inline-flex h-8 w-8 items-center justify-center rounded-lg text-muted-foreground transition-colors hover:bg-muted hover:text-foreground"
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
                  <path d="M18 6 6 18" />
                  <path d="m6 6 12 12" />
                </svg>
              </button>
            </div>

            <button
              type="button"
              onClick={handleStartNewChat}
              disabled={sending}
              className="mx-4 mt-4 inline-flex items-center justify-center gap-2 rounded-2xl border border-[#00A8E8]/30 bg-white/85 px-3 py-2.5 text-sm font-medium text-[#007EA7] shadow-sm transition-all duration-200 hover:border-[#00A8E8]/50 hover:bg-[#00A8E8]/10 disabled:cursor-not-allowed disabled:opacity-60"
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

            <div className="mt-4 flex-1 min-h-0 overflow-y-auto px-4 pb-4">{renderSessionItems()}</div>
          </div>
        </div>
      )}

      <dialog
        ref={deleteDialogRef}
        onCancel={cancelDeleteSession}
        onClose={cancelDeleteSession}
        className="fixed top-1/2 left-1/2 m-0 w-[min(calc(100vw-2rem),24rem)] max-h-[calc(100vh-2rem)] -translate-x-1/2 -translate-y-1/2 rounded-2xl border border-white/60 bg-white/95 p-0 text-foreground shadow-[0_24px_48px_rgba(15,23,42,0.18)] backdrop:bg-[#00171F]/40 backdrop:backdrop-blur-sm open:animate-in"
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
