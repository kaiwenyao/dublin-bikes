import { axiosWithAuth } from './client'
import { USER_ENDPOINTS } from './endpoints'

/** User info / Profile (consistent with backend serialize_user) */
export interface UserProfileVO {
  id: number
  username: string
  email: string
  avatar_url: string | null
  is_active: boolean
  created_at: string | null
}

/**
 * Get current user info (requires auth).
 * If 401/403, client will try to refresh token, redirect to login page on failure.
 */
export const getMeAPI = async (): Promise<UserProfileVO> => {
  const res = await axiosWithAuth.get<UserProfileVO>(USER_ENDPOINTS.me)
  return res.data
}

/**
 * User logout (requires auth, carries current access_token to notify server to terminate session).
 */
export const userLogoutAPI = async (): Promise<void> => {
  await axiosWithAuth.post<void>(USER_ENDPOINTS.logout)
}
