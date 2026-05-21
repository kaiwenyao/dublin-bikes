import request from './request'
import { WEATHER_ENDPOINTS } from './endpoints'

export interface WeatherResponse {
  // Define generic structure first, refine after seeing actual response data
  [key: string]: unknown
}

/**
 * Get weather data (backend uniformly returns Dublin data)
 */
export const getWeatherAPI = async (): Promise<WeatherResponse> => {
  const res = await request.get<WeatherResponse>(WEATHER_ENDPOINTS.get)
  return res.data
}
