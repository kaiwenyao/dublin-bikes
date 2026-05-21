import request from './request'

interface Coordinate {
  lat: number
  lon: number
}

export interface JourneyPlanRequest {
  start?: Coordinate
  end?: Coordinate
  start_address?: string
  end_address?: string
}

export interface RouteStationInfo {
  number: number
  name: string
  address: string
  coords: Coordinate
  walking_time: number
  available_bikes?: number
  available_bike_stands?: number
}

export interface JourneyRouteInfo {
  start_station: RouteStationInfo
  end_station: RouteStationInfo
  cycling_route: {
    cycling_time: number
  }
  total_duration: number
}

export interface SearchContext {
  start_resolved: Coordinate
  end_resolved: Coordinate
}

export interface JourneyPlanResponse {
  route_info: JourneyRouteInfo
  search_context: SearchContext
}

/**
 * Call the backend to plan a journey
 */
export const planJourneyAPI = (data: JourneyPlanRequest) => {
  return request.post<JourneyPlanResponse>('/api/journey/plan', data)
}
