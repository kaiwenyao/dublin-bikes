/** Key for storing access_token returned by backend after login in storage */
export const ACCESS_TOKEN_KEY = 'access_token'

/** Key for storing refresh_token returned by backend after login in storage */
export const REFRESH_TOKEN_KEY = 'refresh_token'

interface AuthTokenPayload {
  accessToken: string
  refreshToken?: string
}

export interface SetAuthTokensOptions {
  /** true = localStorage (persists after closing browser), false = sessionStorage (clears when tab/browser closes) */
  persistent?: boolean
}

/**
 * Return current Storage containing token: prioritize sessionStorage, then localStorage.
 * Used for reading token and writing to same storage during refresh.
 */
const getTokenStorage = (): Storage | null => {
  if (typeof window === 'undefined') return null
  if (window.sessionStorage.getItem(ACCESS_TOKEN_KEY)) return window.sessionStorage
  if (window.localStorage.getItem(ACCESS_TOKEN_KEY)) return window.localStorage
  return null
}

/**
 * Select storage based on "Remember Me": persistent true uses localStorage, otherwise sessionStorage.
 */
const getStorageByPersistent = (persistent: boolean): Storage | null => {
  if (typeof window === 'undefined') return null
  return persistent ? window.localStorage : window.sessionStorage
}

export const getAccessToken = (): string | null =>
  getTokenStorage()?.getItem(ACCESS_TOKEN_KEY) ?? null

export const getRefreshToken = (): string | null =>
  getTokenStorage()?.getItem(REFRESH_TOKEN_KEY) ?? null

/**
 * Write access/refresh token.
 * @param payload - token content
 * @param options.persistent - On login: true uses localStorage, false uses sessionStorage; on refresh don't pass to use current token storage
 */
export const setAuthTokens = (
  { accessToken, refreshToken }: AuthTokenPayload,
  options?: SetAuthTokensOptions
): void => {
  const storage =
    options?.persistent !== undefined
      ? getStorageByPersistent(options.persistent)
      : getTokenStorage() ?? (typeof window !== 'undefined' ? window.localStorage : null)
  if (!storage) return

  storage.setItem(ACCESS_TOKEN_KEY, accessToken)
  if (refreshToken) {
    storage.setItem(REFRESH_TOKEN_KEY, refreshToken)
  }
}

/** Clear tokens from both sessionStorage and localStorage to avoid residue */
export const clearAuthTokens = (): void => {
  if (typeof window === 'undefined') return
  for (const storage of [window.sessionStorage, window.localStorage]) {
    storage.removeItem(ACCESS_TOKEN_KEY)
    storage.removeItem(REFRESH_TOKEN_KEY)
  }
}
