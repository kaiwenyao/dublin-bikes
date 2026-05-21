import { USER_ENDPOINTS } from './endpoints'
import request from './request'
import type { UserProfileVO } from './user'

/** User login request parameters */
export interface UserLoginDTO {
  identifier: string
  password: string
}

/** User login response data */
export interface UserLoginVO {
  access_token: string
  refresh_token: string
  expires_in: number
  token_type: string
}

/**
 * Registration returns structure consistent with user profile, using type alias to reuse definition.
 */
export type UserRegisterVO = UserProfileVO

/** User registration request parameters */
export interface UserRegisterDTO {
  username: string
  email: string
  password: string
}

const normalizeIdentifier = (identifier: string): string => identifier.trim()

const normalizeEmail = (email: string): string => email.trim().toLowerCase()

/**
 * User login.
 * The `identifier` parameter supports username or email, will be auto-trimmed before sending.
 */
export const userLoginAPI = async (data: UserLoginDTO): Promise<UserLoginVO> => {
  const res = await request.post<UserLoginVO>(USER_ENDPOINTS.login, {
    identifier: normalizeIdentifier(data.identifier),
    password: data.password,
  })
  return res.data
}

/**
 * User registration.
 * Username and email in parameters will be normalized to avoid backend validation ambiguity from same value in different formats.
 * After successful registration, account is inactive, need to call activation API to complete email verification.
 */
export const userRegisterAPI = async (data: UserRegisterDTO): Promise<UserRegisterVO> => {
  const res = await request.post<UserRegisterVO>(USER_ENDPOINTS.register, {
    username: normalizeIdentifier(data.username),
    email: normalizeEmail(data.email),
    password: data.password,
  })
  return res.data
}

/**
 * Request verification code to be sent to specified email or email corresponding to username.
 * Inactive users can request at most once per minute.
 */
export const sendVerificationCodeAPI = async (identifier: string): Promise<void> => {
  await request.post(USER_ENDPOINTS.sendVerificationCode, {
    identifier: normalizeIdentifier(identifier),
  })
}

/**
 * Activate account using email or username + 6-digit verification code.
 * After successful activation, can login immediately.
 */
export const activateAccountAPI = async (
  identifier: string,
  code: string
): Promise<UserRegisterVO> => {
  const res = await request.post<UserRegisterVO>(USER_ENDPOINTS.activate, {
    identifier: normalizeIdentifier(identifier),
    code: code.trim(),
  })
  return res.data
}

/**
 * Activate account via activation link Token in email (corresponds to path /activate/:token).
 * After successful activation, can login immediately.
 */
export const activateByTokenAPI = async (token: string): Promise<UserRegisterVO> => {
  const res = await request.post<UserRegisterVO>(USER_ENDPOINTS.activateByToken, {
    token: token.trim(),
  })
  return res.data
}
