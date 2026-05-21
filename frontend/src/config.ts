/**
 * Backend API configuration: requests uniformly go through frontend same-origin /api, forwarded to backend by dev proxy or production reverse proxy.
 * For dev environment see vite.config.ts server.proxy; for production configure /api -> backend address in Nginx etc.
 */
export const API_BASE_URL = ''
