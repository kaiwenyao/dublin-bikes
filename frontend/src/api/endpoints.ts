/**
 * Unified management of API paths to avoid scattering string constants in business code.
 * Note: Only maintains "paths" here, does not include domain and protocol.
 */
const USER_API_PREFIX = '/api/users'
const STATION_API_PREFIX = '/api/stations'
const CHAT_API_PREFIX = '/api/chat'

/** User module interface paths */
export const USER_ENDPOINTS = {
  login: `${USER_API_PREFIX}/login`,
  register: `${USER_API_PREFIX}/register`,
  sendVerificationCode: `${USER_API_PREFIX}/send-verification-code`,
  activate: `${USER_API_PREFIX}/activate`,
  activateByToken: `${USER_API_PREFIX}/activate-by-token`,
  me: `${USER_API_PREFIX}/me`,
  refresh: `${USER_API_PREFIX}/refresh`,
  logout: `${USER_API_PREFIX}/logout`,
} as const

/** Station module interface paths */
export const STATION_ENDPOINTS = {
  list: `${STATION_API_PREFIX}/`,
} as const

/** Weather module interface paths */
export const WEATHER_ENDPOINTS = {
  get: '/api/weather',
} as const

/** Chat module interface paths */
export const CHAT_ENDPOINTS = {
  stream: `${CHAT_API_PREFIX}/stream`,
  sessions: `${CHAT_API_PREFIX}/sessions`,
} as const
