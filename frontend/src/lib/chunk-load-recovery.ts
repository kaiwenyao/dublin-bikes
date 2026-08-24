const RELOAD_AT_KEY = 'vite_preload_reload_at'
const RELOAD_COOLDOWN_MS = 10_000

interface ChunkLoadRecoveryTarget {
  addEventListener(type: string, listener: EventListener): void
  sessionStorage: Pick<Storage, 'getItem' | 'setItem'>
  location: Pick<Location, 'reload'>
}

export function installChunkLoadRecovery(
  target: ChunkLoadRecoveryTarget = window,
  now: () => number = Date.now
): void {
  target.addEventListener('vite:preloadError', (event) => {
    const currentTime = now()
    let lastReloadAt = Number.NaN
    try {
      lastReloadAt = Number(target.sessionStorage.getItem(RELOAD_AT_KEY))
    } catch {
      // Storage can be unavailable in privacy-restricted browser contexts.
    }

    const elapsed = currentTime - lastReloadAt
    if (Number.isFinite(lastReloadAt) && elapsed >= 0 && elapsed < RELOAD_COOLDOWN_MS) {
      return
    }

    event.preventDefault()
    try {
      target.sessionStorage.setItem(RELOAD_AT_KEY, String(currentTime))
    } catch {
      // Reload still recovers stale chunks when session storage is unavailable.
    }
    target.location.reload()
  })
}
