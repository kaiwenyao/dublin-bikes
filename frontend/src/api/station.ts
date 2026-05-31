import request from './request'
import { STATION_ENDPOINTS } from './endpoints'
import { parseBackendUtcDateTime, formatChartAxisTime } from '@/lib/datetime'

/** Station info (consistent with backend station_to_dict) */
export interface StationVO {
  number: number
  contract_name: string
  name: string
  address: string
  latitude: number
  longitude: number
  banking: boolean
  bonus: boolean
  bike_stands: number
}

/**
 * Get all station list (no auth required).
 */
export const getStationsAPI = async (): Promise<StationVO[]> => {
  const res = await request.get<StationVO[]>(STATION_ENDPOINTS.list)
  return res.data ?? []
}



export interface StationAvailabilityVO {
  id?: number;                    // Database ID / Sequence (optional)
  number: number;                 // Station number
  available_bikes: number;        // Number of available bikes
  available_bike_stands: number;  // Number of available bike stands
  bike_stands?: number;           // Total bike stands (optional, as it's missing in the CSV but might be used elsewhere)
  status: string;                 // Station status (e.g., "OPEN")
  last_update: number | string;   // Millisecond timestamp (number based on CSV), keeping string for fallback compatibility
  timestamp: string;              // Database record timestamp
  requested_at: string;           // API request timestamp
}

/**
 * Fetch the latest availability status and historical records for a single station.
 */
export const getStationAvailabilityAPI = async (number: number): Promise<StationAvailabilityVO[]> => {
  try {
  // Send request
    const res = await request.get<unknown>(`/api/stations/${number}/availability`);
    
    let actualData: unknown = res;

    // 1. Peel first layer: Axios response.data wrapper (if exists)
    if (actualData !== null && typeof actualData === 'object' && 'data' in actualData) {
      actualData = (actualData as Record<string, unknown>).data;
    }

    // 2. Peel second layer: Backend may wrap { data: [...] } (only peel if it's not array)
    if (actualData !== null && typeof actualData === 'object' && !Array.isArray(actualData) && 'data' in actualData) {
      actualData = (actualData as Record<string, unknown>).data;
    }

    // 3. Ensure type safety and convert back to our format
    const finalData = actualData as StationAvailabilityVO | StationAvailabilityVO[];

    if (!finalData) return [];

    // 4. Uniformly wrap as "array" and return
    return Array.isArray(finalData) ? finalData : [finalData];

  } catch (error) {
    console.error(`Failed to fetch availability data for station ${number}:`, error);
    return []; 
  }
}


export const getStationsStatusAPI = async (): Promise<StationAvailabilityVO[]> => {
  try {
    const res = await request.get<unknown>('/api/stations/status')
    
    // Add : unknown to let TypeScript know we manually peel outer layers
    let actualData: unknown = res;

    // 1. Peel first layer: Axios response.data wrapper (if exists)
    if (actualData !== null && typeof actualData === 'object' && 'data' in actualData) {
      actualData = (actualData as Record<string, unknown>).data;
    }

    // 2. Peel second layer: Flask backend { code: 0, data: [...] } wrapper
    if (actualData !== null && typeof actualData === 'object' && !Array.isArray(actualData) && 'data' in actualData) {
      actualData = (actualData as Record<string, unknown>).data;
    }

    // 3. Ensure final result is array
    return Array.isArray(actualData) ? actualData : [];
  } catch (error) {
    console.error('Failed to fetch all stations status:', error);
    return [];
  }
}


/** Prediction data (consistent with JSON structure returned by backend) */
export interface PredictionResponseVO {
  forecast_time: string;
  predicted_available_bikes: number;
}

/** Unified format for Recharts chart usage after transformation */
export interface ChartData {
  forecastTime: string;  // Raw UTC datetime from backend, e.g. "2026-05-31T15:00:00"
  timeLabel: string;     // Local HH:MM for X axis
  bikes: number;
  isPrediction: boolean;
}

/**
 * Get future bike prediction count for station
 * @param number Station ID
 * @param hours Number of future hours to slice (e.g., 4 or 24)
 */
export const getStationPredictionAPI = async (number: number, hours: number): Promise<ChartData[]> => {
  try {
    const res = await request.get<unknown>(`/api/stations/${number}/prediction`);
    
    let actualData: unknown = res;

    // 1. Peel first layer: Axios response.data wrapper (if exists)
    if (actualData !== null && typeof actualData === 'object' && 'data' in actualData) {
      actualData = (actualData as Record<string, unknown>).data;
    }

    // 2. Peel second layer: Flask backend { code: 0, data: [...] } wrapper
    if (actualData !== null && typeof actualData === 'object' && !Array.isArray(actualData) && 'data' in actualData) {
      actualData = (actualData as Record<string, unknown>).data;
    }

    const finalData = actualData as PredictionResponseVO[];

    if (!Array.isArray(finalData)) return [];

    // 3. Slice corresponding hours based on user selection
    const slicedData = finalData.slice(0, hours);

    // 4. Transform to format needed by Recharts chart (display in user's local timezone)
    return slicedData.map(item => {
      const date = parseBackendUtcDateTime(item.forecast_time)

      return {
        forecastTime: item.forecast_time,
        timeLabel: formatChartAxisTime(date),
        bikes: item.predicted_available_bikes,
        isPrediction: true,
      }
    })

  } catch (error) {
    console.error(`Failed to fetch prediction data for station ${number}:`, error);
    return [];
  }
}