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

const storages = (): Storage[] => {
  if (typeof window === 'undefined') return []
  return [window.sessionStorage, window.localStorage]
}

const clearAuthTokensIn = (storage: Storage): void => {
  storage.removeItem(ACCESS_TOKEN_KEY)
  storage.removeItem(REFRESH_TOKEN_KEY)
}

/**
 * Return access/refresh from the first storage that has a valid access token.
 * Prefer sessionStorage so an active tab session wins over stale localStorage leftovers.
 */
const readActiveTokenPair = (): {
  access: string | null
  refresh: string | null
  storage: Storage | null
} => {
  for (const storage of storages()) {
    const access = storage.getItem(ACCESS_TOKEN_KEY)
    if (!isValidToken(access)) continue
    const refresh = storage.getItem(REFRESH_TOKEN_KEY)
    return {
      access,
      refresh: isValidToken(refresh) ? refresh : null,
      storage,
    }
  }
  return { access: null, refresh: null, storage: null }
}

/**
 * Storage used for refresh writes: same as the active token pair above.
 */
const getTokenStorage = (): Storage | null => readActiveTokenPair().storage

/**
 * Select storage based on "Remember Me": persistent true uses localStorage, otherwise sessionStorage.
 */
const getStorageByPersistent = (persistent: boolean): Storage | null => {
  if (typeof window === 'undefined') return null
  return persistent ? window.localStorage : window.sessionStorage
}

export const getAccessToken = (): string | null => readActiveTokenPair().access

export const getRefreshToken = (): string | null => readActiveTokenPair().refresh

/**
 * Write access/refresh token.
 * @param payload - token content
 * @param options.persistent - On login: true uses localStorage, false uses sessionStorage; on refresh don't pass to use current token storage
 */
export const setAuthTokens = (
  { accessToken, refreshToken }: AuthTokenPayload,
  options?: SetAuthTokensOptions
): void => {
  const explicitPersistent = options?.persistent
  const storage =
    explicitPersistent !== undefined
      ? getStorageByPersistent(explicitPersistent)
      : getTokenStorage() ?? (typeof window !== 'undefined' ? window.localStorage : null)
  if (!storage) return

  const existingRefresh = storage.getItem(REFRESH_TOKEN_KEY)
  const refreshToStore: string | null =
    refreshToken !== undefined && isValidToken(refreshToken)
      ? refreshToken
      : isValidToken(existingRefresh)
        ? existingRefresh
        : null

  if (explicitPersistent === true) {
    // Remember-me login: drop stale session tokens so they cannot override local reads.
    clearAuthTokensIn(window.sessionStorage)
    clearAuthTokensIn(window.localStorage)
  } else if (explicitPersistent === false) {
    // Session-only login: do not touch localStorage (shared across tabs).
    clearAuthTokensIn(window.sessionStorage)
  } else {
    // Refresh: update only the active storage backing the current token pair.
    clearAuthTokensIn(storage)
  }

  storage.setItem(ACCESS_TOKEN_KEY, accessToken)
  if (isValidToken(refreshToStore)) {
    storage.setItem(REFRESH_TOKEN_KEY, refreshToStore)
  }
}

/** Clear tokens from both sessionStorage and localStorage (logout). */
export const clearAuthTokens = (): void => {
  for (const storage of storages()) {
    clearAuthTokensIn(storage)
  }
}
