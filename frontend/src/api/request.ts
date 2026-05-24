import axios, {
  type AxiosError,
  type AxiosInstance,
  type AxiosResponse,
  type InternalAxiosRequestConfig,
} from 'axios'
import { toast } from 'sonner'
import { API_BASE_URL } from '@/config'
import { STATION_ENDPOINTS, USER_ENDPOINTS } from './endpoints'
import { clearAuthTokens, getAccessToken, getRefreshToken, setAuthTokens } from './token'

const JSON_HEADERS = { 'Content-Type': 'application/json' }
const REQUEST_TIMEOUT_MS = 15_000

/** Create axios instance with unified base configuration. */
const createHttpClient = (): AxiosInstance =>
  axios.create({
    baseURL: API_BASE_URL || undefined,
    headers: JSON_HEADERS,
    timeout: REQUEST_TIMEOUT_MS,
  })

/** Only used for token refresh, no interceptors mounted, avoid loops */
const refreshClient = createHttpClient()

/** Unified business request instance (used globally) */
const request: AxiosInstance = createHttpClient()

const LOGIN_EXPIRED_MESSAGE = 'Session expired. Please sign in again.'
const LOGIN_REDIRECT_TOAST = 'Session expired. Redirecting to sign in.'
const NETWORK_ERROR_MESSAGE = 'Network error'
const LOGIN_PATH = '/login'
const AUTH_EXEMPT_ENDPOINTS = [
  USER_ENDPOINTS.login,
  USER_ENDPOINTS.register,
  USER_ENDPOINTS.sendVerificationCode,
  USER_ENDPOINTS.activate,
  USER_ENDPOINTS.activateByToken,
  STATION_ENDPOINTS.list,
]

/** Backend unified response format */
export interface ApiResult<T = unknown> {
  code?: number
  msg?: string
  data?: T
}

/** Compatible with backend historical convention: 0/1 both considered success */
const SUCCESS_CODES = new Set([0, 1])
const DEFAULT_UNWRAP_MESSAGE = 'Request failed'

const isApiSuccessCode = (code: number | undefined): boolean => (code != null ? SUCCESS_CODES.has(code) : false)

const isApiResult = (payload: unknown): payload is ApiResult<unknown> =>
  typeof payload === 'object' && payload != null && 'code' in payload

const unwrapApiResult = <T>(result: ApiResult<T> | undefined, fallbackMessage: string): T => {
  if (isApiSuccessCode(result?.code) && result?.data != null) {
    return result.data
  }
  throw new Error(result?.msg ?? fallbackMessage)
}

interface BackendErrorPayload {
  msg?: unknown
}

/**
 * Extract more user-friendly error message from axios error.
 */
const extractErrorMessage = (error: unknown, fallbackMessage = NETWORK_ERROR_MESSAGE): string => {
  if (axios.isAxiosError(error)) {
    const data = error.response?.data as BackendErrorPayload | undefined
    if (typeof data?.msg === 'string' && data.msg.trim()) {
      return data.msg
    }
    if (!error.response && error.request) {
      return 'Request sent but no response received. Please check the server or proxy.'
    }
    if (error.message) {
      return error.message
    }
  }

  if (error instanceof Error && error.message) {
    return error.message
  }

  return fallbackMessage
}

/** Normalize any error into Error object for unified handling across the chain. */
const normalizeError = (error: unknown, fallbackMessage?: string): Error =>
  new Error(extractErrorMessage(error, fallbackMessage))

/** Wrap normalized error as Promise.reject, compatible with axios interceptor signature. */
const rejectWithNormalizedError = (error: unknown, fallbackMessage?: string): Promise<never> =>
  Promise.reject(normalizeError(error, fallbackMessage))

/**
 * Unified backend business code handling and unwrapping: when response body is { code, msg, data },
 * non-success codes throw directly; on success replace response.data with data field (business side gets T directly).
 */
const handleBusinessResponse = <T>(response: AxiosResponse<unknown>): AxiosResponse<T> => {
  const payload = response.data as unknown
  if (!isApiResult(payload)) {
    return response as AxiosResponse<T>
  }
  if (!isApiSuccessCode(payload.code)) {
    throw new Error(payload.msg ?? DEFAULT_UNWRAP_MESSAGE)
  }
  ;(response as { data: unknown }).data = payload.data
  return response as AxiosResponse<T>
}

/**
 * Refresh concurrency control:
 * - `isRefreshing` ensures only one refresh request at a time.
 * - Other failed requests enter `failedQueue`, wait for refresh result then retry/fail uniformly.
 */
let isRefreshing = false

type PendingRequest = { resolve: (token: string) => void; reject: (error: Error) => void }
const failedQueue: PendingRequest[] = []

interface RefreshTokenVO {
  access_token: string
  refresh_token?: string
}

type RetryableRequest = InternalAxiosRequestConfig & { _retry?: boolean }

/**
 * Uniformly handle waiting queue after refresh completes.
 */
const processQueue = (error: Error | null, token: string | null): void => {
  failedQueue.forEach(({ resolve, reject }) => {
    if (error || !token) {
      reject(error ?? new Error(LOGIN_EXPIRED_MESSAGE))
    } else {
      resolve(token)
    }
  })
  failedQueue.length = 0
}

/**
 * Silently refresh access_token using refresh_token.
 * On failure clear local token, let upper layer handle expiration process.
 */
const refreshAccessToken = async (): Promise<string | null> => {
  const refreshToken = getRefreshToken()
  if (!refreshToken) return null

  try {
    const { data } = await refreshClient.post<ApiResult<RefreshTokenVO>>(USER_ENDPOINTS.refresh, {
      refresh_token: refreshToken,
    })
    const result = unwrapApiResult(data, 'Session expired.')
    setAuthTokens({
      accessToken: result.access_token,
      refreshToken: result.refresh_token,
    })
    return result.access_token
  } catch {
    clearAuthTokens()
    return null
  }
}

/**
 * Resolve available access_token before request: use directly if exists, otherwise try silent refresh
 */
const resolveAccessToken = async (): Promise<string | null> => {
  const accessToken = getAccessToken()
  if (accessToken) return accessToken
  const refreshToken = getRefreshToken()
  if (!refreshToken) return null
  return refreshAccessToken()
}

/** Login/register endpoints don't go through refresh retry logic to avoid meaningless loops. */
const isAuthEndpoint = (url?: string): boolean => {
  if (!url) return false
  return AUTH_EXEMPT_ENDPOINTS.some((path) => url.includes(path))
}

/**
 * Clear local token and redirect to login page
 */
const handleTokenExpired = (): void => {
  clearAuthTokens()
  if (typeof window === 'undefined') return
  if (window.location.pathname === LOGIN_PATH) return

  window.history.replaceState({}, '', LOGIN_PATH)
  window.dispatchEvent(new PopStateEvent('popstate'))
}

const notifyTokenExpired = (): void => {
  toast.warning(LOGIN_REDIRECT_TOAST)
  handleTokenExpired()
}

const setAuthorizationHeader = (config: InternalAxiosRequestConfig, token: string): void => {
  if (!config.headers) {
    config.headers = {} as InternalAxiosRequestConfig['headers']
  }
  config.headers.Authorization = `Bearer ${token}`
}

// ============ Request Interceptor ============
request.interceptors.request.use(
  async (config: InternalAxiosRequestConfig) => {
    const token = await resolveAccessToken()
    if (token) setAuthorizationHeader(config, token)
    return config
  },
  (error) => rejectWithNormalizedError(error, NETWORK_ERROR_MESSAGE)
)

// ============ Response Interceptor ============
request.interceptors.response.use(
  (response) => handleBusinessResponse(response),
  async (error: AxiosError) => {
    const originalRequest = error.config as RetryableRequest | undefined
    const status = error.response?.status

    // 401/403 and not login/register endpoint: try refresh token then retry
    if (
      (status === 401 || status === 403) &&
      originalRequest &&
      !originalRequest._retry &&
      !isAuthEndpoint(originalRequest.url)
    ) {
      if (isRefreshing) {
        return new Promise<string>((resolve, reject) => {
          failedQueue.push({ resolve, reject })
        })
          .then((newToken) => {
            setAuthorizationHeader(originalRequest, newToken)
            return request(originalRequest)
          })
      }

      originalRequest._retry = true
      isRefreshing = true

      try {
        const newToken = await refreshAccessToken()
        if (newToken) {
          processQueue(null, newToken)
          setAuthorizationHeader(originalRequest, newToken)
          return request(originalRequest)
        }
        processQueue(new Error(LOGIN_EXPIRED_MESSAGE), null)
        notifyTokenExpired()
        return Promise.reject(normalizeError(error, LOGIN_EXPIRED_MESSAGE))
      } catch (refreshError) {
        const err = normalizeError(refreshError, LOGIN_EXPIRED_MESSAGE)
        processQueue(err, null)
        notifyTokenExpired()
        return Promise.reject(err)
      } finally {
        isRefreshing = false
      }
    }

    // Other errors: unified toast and reject
    const message = extractErrorMessage(error, NETWORK_ERROR_MESSAGE)
    toast.error(message)
    return Promise.reject(new Error(message))
  }
)

export default request

/** For non-axios requests (like SSE): first get current access_token, if none then silent refresh with refresh_token and return */
export { resolveAccessToken, refreshAccessToken }
