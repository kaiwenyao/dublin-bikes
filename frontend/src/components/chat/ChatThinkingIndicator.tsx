export function ChatThinkingIndicator() {
  return (
    <div className="flex items-center gap-2" aria-live="polite" aria-busy="true">
      <span className="text-sm text-muted-foreground">Thinking...</span>
      <div className="flex gap-1.5" aria-hidden>
        <span className="h-2 w-2 rounded-full bg-muted-foreground/60 animate-bounce [animation-delay:-0.3s]" />
        <span className="h-2 w-2 rounded-full bg-muted-foreground/60 animate-bounce [animation-delay:-0.15s]" />
        <span className="h-2 w-2 rounded-full bg-muted-foreground/60 animate-bounce" />
      </div>
    </div>
  )
}
