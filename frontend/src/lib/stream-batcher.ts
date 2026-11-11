/**
 * Coalesces high-frequency streaming chunks into at most one state update per
 * `intervalMs`.
 *
 * Server-sent token chunks arrive far faster than React needs to repaint. Feeding
 * each one into `setMessages` re-renders the whole conversation and re-parses the
 * streaming Markdown on every token, which is quadratic in the answer length.
 *
 * A timer (not `requestAnimationFrame`) drives the flush because `fetchEventSource`
 * keeps streaming while the tab is hidden (`openWhenHidden: true`), and rAF does
 * not fire in background tabs — buffered text would never render.
 */
export interface StreamBatcher {
  /** Append a chunk; the first chunk schedules a flush. */
  push(chunk: string): void
  /** Flush immediately (end of stream, error, or abort); no-op when empty. */
  flush(): void
  /** Drop any buffered text without flushing (component unmount). */
  cancel(): void
}

export interface StreamBatcherOptions {
  /** Max delay between a chunk and the state update. Defaults to 50 ms. */
  intervalMs?: number
  /** Test seams; default to global setTimeout/clearTimeout. */
  setTimer?: (callback: () => void, ms: number) => unknown
  clearTimer?: (handle: unknown) => void
}

export function createStreamBatcher(
  onFlush: (text: string) => void,
  options: StreamBatcherOptions = {}
): StreamBatcher {
  const intervalMs = options.intervalMs ?? 50
  const setTimer =
    options.setTimer ??
    ((callback: () => void, ms: number) => setTimeout(callback, ms))
  const clearTimer =
    options.clearTimer ??
    ((handle: unknown) => clearTimeout(handle as ReturnType<typeof setTimeout>))

  let buffer = ''
  let timer: unknown = null

  const clearPending = () => {
    if (timer !== null) {
      clearTimer(timer)
      timer = null
    }
  }

  const flush = () => {
    clearPending()
    if (!buffer) return
    const text = buffer
    buffer = ''
    onFlush(text)
  }

  return {
    push(chunk) {
      if (!chunk) return
      buffer += chunk
      if (timer === null) {
        timer = setTimer(() => {
          timer = null
          flush()
        }, intervalMs)
      }
    },
    flush,
    cancel() {
      clearPending()
      buffer = ''
    },
  }
}
