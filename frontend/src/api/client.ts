/**
 * Compatibility alias:
 * `axiosWithAuth` and `request` point to the same axios instance.
 * This instance automatically carries token, tries to refresh and retry on 401/403, and unwraps business response.
 */
export { default as axiosWithAuth } from './request'
export { ACCESS_TOKEN_KEY, REFRESH_TOKEN_KEY } from './token'
