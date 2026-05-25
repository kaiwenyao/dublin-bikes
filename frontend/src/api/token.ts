/** Key for storing access_token returned by backend after login in storage */
export const ACCESS_TOKEN_KEY = 'access_token'

/** Key for storing refresh_token returned by backend after login in storage */
export const REFRESH_TOKEN_KEY = 'refresh_token'

/** Per-tab preference for which storage should back outgoing requests. */
const ACTIVE_TOKEN_STORAGE_KEY = 'active_token_storage'
const ACTIVE_TOKEN_SUBJECT_KEY = 'active_token_subject'
const SESSION_STORAGE_VALUE = 'session'
const LOCAL_STORAGE_VALUE = 'local'

/** Seconds before JWT exp to treat access token as expired (clock skew). */
const ACCESS_TOKEN_EXPIRY_SKEW_SECONDS = 30

interface AuthTokenPayload {
  accessToken: string
  refreshToken?: string
}

export interface SetAuthTokensOptions {
  /** true = localStorage (persists after closing browser), false = sessionStorage (clears when tab/browser closes) */
  persistent?: boolean
}

interface TokenPairCandidate {
  access: string
  refresh: string | null
  storage: Storage
  accessExpired: boolean
  subject: string | null
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

const storagePreferenceValue = (storage: Storage): string | null => {
  if (typeof window === 'undefined') return null
  if (storage === window.sessionStorage) return SESSION_STORAGE_VALUE
  if (storage === window.localStorage) return LOCAL_STORAGE_VALUE
  return null
}

const tokenSubject = (token: string): string | null => {
  const payload = decodeJwtPayload(token)
  const subject = payload?.sub
  return typeof subject === 'string' && subject.trim() !== '' ? subject : null
}

const setActiveStorage = (storage: Storage, accessToken: string): void => {
  if (typeof window === 'undefined') return
  const value = storagePreferenceValue(storage)
  if (value) window.sessionStorage.setItem(ACTIVE_TOKEN_STORAGE_KEY, value)
  const subject = tokenSubject(accessToken)
  if (subject) {
    window.sessionStorage.setItem(ACTIVE_TOKEN_SUBJECT_KEY, subject)
  } else {
    window.sessionStorage.removeItem(ACTIVE_TOKEN_SUBJECT_KEY)
  }
}

const getPreferredStorage = (): Storage | null => {
  if (typeof window === 'undefined') return null
  const value = window.sessionStorage.getItem(ACTIVE_TOKEN_STORAGE_KEY)
  if (value === SESSION_STORAGE_VALUE) return window.sessionStorage
  if (value === LOCAL_STORAGE_VALUE) return window.localStorage
  return null
}

const getActiveSubject = (): string | null => {
  if (typeof window === 'undefined') return null
  const subject = window.sessionStorage.getItem(ACTIVE_TOKEN_SUBJECT_KEY)
  return subject != null && subject.trim() !== '' ? subject : null
}

const decodeJwtPayload = (token: string): Record<string, unknown> | null => {
  const parts = token.split('.')
  if (parts.length !== 3) return null
  try {
    const base64 = parts[1].replace(/-/g, '+').replace(/_/g, '/')
    const padded = base64.padEnd(base64.length + ((4 - (base64.length % 4)) % 4), '=')
    return JSON.parse(atob(padded)) as Record<string, unknown>
  } catch {
    return null
  }
}

const isAccessTokenExpired = (accessToken: string): boolean => {
  const payload = decodeJwtPayload(accessToken)
  const exp = payload?.exp
  if (typeof exp !== 'number') return true
  return Date.now() / 1000 >= exp - ACCESS_TOKEN_EXPIRY_SKEW_SECONDS
}

const readTokenPairFromStorage = (storage: Storage): TokenPairCandidate | null => {
  const access = storage.getItem(ACCESS_TOKEN_KEY)
  if (!isValidToken(access)) return null
  const refresh = storage.getItem(REFRESH_TOKEN_KEY)
  return {
    access,
    refresh: isValidToken(refresh) ? refresh : null,
    storage,
    accessExpired: isAccessTokenExpired(access),
    subject: tokenSubject(access),
  }
}

/** Prefer non-expired access; without an explicit tab preference, localStorage wins ties. */
const compareTokenPairCandidates = (a: TokenPairCandidate, b: TokenPairCandidate): number => {
  if (a.accessExpired !== b.accessExpired) return a.accessExpired ? 1 : -1
  const aIsLocal = a.storage === window.localStorage
  const bIsLocal = b.storage === window.localStorage
  if (aIsLocal !== bIsLocal) return aIsLocal ? -1 : 1
  return 0
}

const listTokenPairCandidates = (): TokenPairCandidate[] =>
  storages()
    .map(readTokenPairFromStorage)
    .filter((candidate): candidate is TokenPairCandidate => candidate != null)
    .sort(compareTokenPairCandidates)

/**
 * Pick the best token pair for this tab: honor the last explicit login/refresh storage
 * when it can provide a valid access token, otherwise use a same-subject non-expired
 * access token before refreshing the preferred storage.
 */
const readActiveTokenPair = (): {
  access: string | null
  refresh: string | null
  storage: Storage | null
} => {
  const candidates = listTokenPairCandidates()
  if (candidates.length === 0) {
    return { access: null, refresh: null, storage: null }
  }

  const preferredStorage = getPreferredStorage()
  const activeSubject = getActiveSubject()
  const preferred = preferredStorage
    ? candidates.find((c) => c.storage === preferredStorage && !c.accessExpired)
    : undefined
  if (preferred) {
    return {
      access: preferred.access,
      refresh: preferred.refresh,
      storage: preferred.storage,
    }
  }

  const preferredWithRefresh = preferredStorage
    ? candidates.find((c) => c.storage === preferredStorage && c.refresh != null)
    : undefined
  const preferredSubject = preferredWithRefresh?.subject ?? activeSubject

  const sameSubjectValidAccess = preferredSubject
    ? candidates.find((c) => !c.accessExpired && c.subject === preferredSubject)
    : undefined
  if (sameSubjectValidAccess) {
    return {
      access: sameSubjectValidAccess.access,
      refresh: sameSubjectValidAccess.refresh,
      storage: sameSubjectValidAccess.storage,
    }
  }

  const withValidAccess = candidates.find((c) => !c.accessExpired)
  if (withValidAccess) {
    if (preferredWithRefresh && preferredSubject && withValidAccess.subject !== preferredSubject) {
      return {
        access: null,
        refresh: preferredWithRefresh.refresh,
        storage: preferredWithRefresh.storage,
      }
    }
    return {
      access: withValidAccess.access,
      refresh: withValidAccess.refresh,
      storage: withValidAccess.storage,
    }
  }

  if (preferredWithRefresh) {
    return {
      access: null,
      refresh: preferredWithRefresh.refresh,
      storage: preferredWithRefresh.storage,
    }
  }

  const withRefresh = candidates.find((c) => c.refresh != null)
  if (withRefresh) {
    return { access: null, refresh: withRefresh.refresh, storage: withRefresh.storage }
  }

  const fallback = candidates[0]
  return { access: null, refresh: null, storage: fallback.storage }
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
  setActiveStorage(storage, accessToken)
}

/** Clear tokens from both sessionStorage and localStorage (logout). */
export const clearAuthTokens = (): void => {
  for (const storage of storages()) {
    clearAuthTokensIn(storage)
  }
  if (typeof window !== 'undefined') {
    window.sessionStorage.removeItem(ACTIVE_TOKEN_STORAGE_KEY)
    window.sessionStorage.removeItem(ACTIVE_TOKEN_SUBJECT_KEY)
  }
}
