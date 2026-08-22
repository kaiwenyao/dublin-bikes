export function ChatThinkingIndicator() {
  return (
    <div className="flex items-center gap-3" aria-live="polite" aria-busy="true">
      <div className="flex items-center gap-1.5" aria-hidden>
        <span className="h-2.5 w-2.5 animate-bounce rounded-full bg-primary/60 [animation-delay:-0.3s]" />
        <span className="h-2.5 w-2.5 animate-bounce rounded-full bg-primary/80 [animation-delay:-0.15s]" />
        <span className="h-2.5 w-2.5 animate-bounce rounded-full bg-primary" />
      </div>
      <span className="text-xs font-medium tracking-wide text-muted-foreground">
        Thinking…
      </span>
    </div>
  )
}