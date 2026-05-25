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

const isValidToken = (value: string | null): value is string =>
  value != null && value !== '' && value !== 'null' && value !== 'undefined'

/**
 * Return current Storage containing a valid token: prioritize localStorage, then sessionStorage.
 * Used for writing to same storage during refresh.
 */
const getTokenStorage = (): Storage | null => {
  if (typeof window === 'undefined') return null
  const localAccess = window.localStorage.getItem(ACCESS_TOKEN_KEY)
  const sessionAccess = window.sessionStorage.getItem(ACCESS_TOKEN_KEY)
  if (isValidToken(localAccess)) return window.localStorage
  if (isValidToken(sessionAccess)) return window.sessionStorage
  return null
}

/**
 * Select storage based on "Remember Me": persistent true uses localStorage, otherwise sessionStorage.
 */
const getStorageByPersistent = (persistent: boolean): Storage | null => {
  if (typeof window === 'undefined') return null
  return persistent ? window.localStorage : window.sessionStorage
}

export const getAccessToken = (): string | null => {
  if (typeof window === 'undefined') return null
  const local = window.localStorage.getItem(ACCESS_TOKEN_KEY)
  if (isValidToken(local)) return local
  const session = window.sessionStorage.getItem(ACCESS_TOKEN_KEY)
  if (isValidToken(session)) return session
  return null
}

export const getRefreshToken = (): string | null => {
  if (typeof window === 'undefined') return null
  const local = window.localStorage.getItem(REFRESH_TOKEN_KEY)
  if (isValidToken(local)) return local
  const session = window.sessionStorage.getItem(REFRESH_TOKEN_KEY)
  if (isValidToken(session)) return session
  return null
}

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

  clearAuthTokens()
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
