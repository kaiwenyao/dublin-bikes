/**
 * Pure reducers for the chat message list.
 *
 * Extracted from the Chat page so the streaming lifecycle can be unit-tested
 * without a DOM: the stop-generation path depends on appending flushed text
 * *before* dropping a bubble that still looks empty.
 */
export interface ChatMessageLike {
  id: string
  role: 'user' | 'assistant'
  content: string
}

/** Append streamed text to one assistant message; other messages keep their identity. */
export function appendToAssistant<T extends ChatMessageLike>(
  messages: readonly T[],
  assistantId: string,
  text: string
): T[] {
  return messages.map((message) =>
    message.id === assistantId
      ? { ...message, content: message.content + text }
      : message
  )
}

/**
 * Remove a trailing assistant bubble that never received content.
 *
 * Streaming chunks are buffered for up to 50 ms, so "empty" here means "nothing
 * has been flushed yet" — callers must flush the active batcher first or the
 * first chunk is dropped together with the bubble.
 */
export function dropTrailingEmptyAssistant<T extends ChatMessageLike>(
  messages: readonly T[]
): T[] {
  const last = messages[messages.length - 1]
  if (last && last.role === 'assistant' && last.content.trim() === '') {
    return messages.slice(0, -1)
  }
  return messages as T[]
}
