/**
 * API layer unified export:
 * - axios client (request / axiosWithAuth)
 * - token utilities
 * - business API (auth / user modules)
 * - common response types
 */
export { default as request } from './request'
export { axiosWithAuth } from './client'
export {
  ACCESS_TOKEN_KEY,
  REFRESH_TOKEN_KEY,
  getAccessToken,
  setAuthTokens,
  clearAuthTokens,
} from './token'
export type { SetAuthTokensOptions } from './token'
export type { ApiResult } from './request'
export {
  userLoginAPI,
  userRegisterAPI,
  sendVerificationCodeAPI,
  activateAccountAPI,
  activateByTokenAPI,
  type UserLoginDTO,
  type UserLoginVO,
  type UserRegisterDTO,
  type UserRegisterVO,
} from './auth'
export {
  getMeAPI,
  userLogoutAPI,
  type UserProfileVO,
} from './user'
