import { useEffect, useState } from 'react'
import { getWeatherAPI } from '@/api/weather'

// Weather data type definition
interface WeatherData {
  current: {
    temperature: number
    icon: 'sunny' | 'cloudy' | 'rainy'
  }
  forecast: Array<{
    time: string
    temperature: number
    icon: 'sunny' | 'cloudy' | 'rainy'
  }>
}



type UnknownObject = Record<string, unknown>

function isObject(value: unknown): value is UnknownObject {
  return typeof value === 'object' && value !== null
}

function asObject(value: unknown): UnknownObject | undefined {
  return isObject(value) ? value : undefined
}

function pickNumber(...candidates: unknown[]): number | undefined {
  for (const candidate of candidates) {
    if (typeof candidate === 'number') return candidate
  }
  return undefined
}

function pickString(...candidates: unknown[]): string | undefined {
  for (const candidate of candidates) {
    if (typeof candidate === 'string') return candidate
  }
  return undefined
}

function pickWeatherCode(...candidates: unknown[]): number | string | undefined {
  for (const candidate of candidates) {
    if (typeof candidate === 'number' || typeof candidate === 'string') return candidate
  }
  return undefined
}

function pickTime(...candidates: unknown[]): string | number | Date | undefined {
  for (const candidate of candidates) {
    if (typeof candidate === 'string' || typeof candidate === 'number' || candidate instanceof Date) {
      return candidate
    }
  }
  return undefined
}

function getFirstWeatherEntry(source: UnknownObject): UnknownObject | undefined {
  if (!Array.isArray(source.weather)) return undefined
  const first = source.weather[0]
  return asObject(first)
}

function extractWeatherMeta(source: UnknownObject): { code: number | string | undefined; description: string | undefined } {
  const weather = getFirstWeatherEntry(source)
  const condition = asObject(source.condition)

  return {
    code: pickWeatherCode(weather?.id, source.weather_code, condition?.code),
    description: pickString(weather?.description, condition?.text, source.description),
  }
}

function createFallbackWeatherData(): WeatherData {
  return {
    current: {
      temperature: 12,
      icon: 'cloudy',
    },
    forecast: [
      { time: '13:00', temperature: 12, icon: 'sunny' },
      { time: '14:00', temperature: 13, icon: 'cloudy' },
      { time: '15:00', temperature: 14, icon: 'sunny' },
      { time: '16:00', temperature: 14, icon: 'sunny' },
      { time: '17:00', temperature: 11, icon: 'rainy' },
    ],
  }
}

// Weather icon component
function WeatherIcon({ type }: { type: 'sunny' | 'cloudy' | 'rainy' }) {
  if (type === 'sunny') {
    return (
      <svg
        viewBox="0 0 24 24"
        fill="none"
        stroke="currentColor"
        strokeWidth="2"
        strokeLinecap="round"
        strokeLinejoin="round"
        className="h-8 w-8"
      >
        <circle cx="12" cy="12" r="4" />
        <path d="M12 2v2M12 20v2M4.93 4.93l1.41 1.41M17.66 17.66l1.41 1.41M2 12h2M20 12h2M6.34 17.66l-1.41 1.41M19.07 4.93l-1.41 1.41" />
      </svg>
    )
  }
  if (type === 'cloudy') {
    return (
      <svg
        viewBox="0 0 24 24"
        fill="none"
        stroke="currentColor"
        strokeWidth="2"
        strokeLinecap="round"
        strokeLinejoin="round"
        className="h-8 w-8"
      >
        <path d="M17.5 19H9a7 7 0 1 1 6.71-9h1.79a4.5 4.5 0 1 1 0 9Z" />
      </svg>
    )
  }
  // rainy
  return (
    <svg
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="2"
      strokeLinecap="round"
      strokeLinejoin="round"
      className="h-6 w-6"
    >
      <path d="M17.5 19H9a7 7 0 1 1 6.71-9h1.79a4.5 4.5 0 1 1 0 9Z" />
      <path d="M12 16v6" />
      <path d="M8 18v6" />
      <path d="M16 18v6" />
    </svg>
  )
}

/** Kelvin to Celsius conversion */
function kelvinToCelsius(kelvin: number): number {
  return kelvin - 273.15
}

/** If Kelvin (typical range ~200-320) convert to Celsius, otherwise keep original value (assumed to be Celsius) */
function toCelsius(value: number): number {
  if (value > 150) {
    return kelvinToCelsius(value)
  }
  return value
}

// Convert weather code or description to icon type
function getWeatherIcon(weatherCode: number | string | undefined, description?: string): 'sunny' | 'cloudy' | 'rainy' {
  if (typeof weatherCode === 'number') {
    // Determine based on weather code (common weather API codes)
    if (weatherCode >= 200 && weatherCode < 600) return 'rainy'
    if (weatherCode >= 800 && weatherCode < 900) {
      if (weatherCode === 800) return 'sunny'
      return 'cloudy'
    }
  }
  if (description) {
    const desc = description.toLowerCase()
    if (desc.includes('rain') || desc.includes('drizzle') || desc.includes('storm')) return 'rainy'
    if (desc.includes('clear') || desc.includes('sun')) return 'sunny'
    return 'cloudy'
  }
  return 'cloudy'
}

// Format time as HH:mm
function formatTime(timeString: string | number | Date | undefined): string {
  if (!timeString) return '00:00'
  try {
    let date: Date
    if (typeof timeString === 'number') {
      // Unix timestamp (seconds or milliseconds)
      date = timeString > 1e10 ? new Date(timeString) : new Date(timeString * 1000)
    } else if (typeof timeString === 'string') {
      // ISO string or HH:mm format
      if (timeString.includes('T') || timeString.includes(' ')) {
        date = new Date(timeString)
      } else if (timeString.match(/^\d{2}:\d{2}$/)) {
        // Already in HH:mm format
        return timeString
      } else {
        date = new Date(timeString)
      }
    } else {
      date = timeString
    }

    if (isNaN(date.getTime())) {
      return '00:00'
    }

    return date.toLocaleTimeString('en-US', { hour: '2-digit', minute: '2-digit', hour12: false })
  } catch {
    return '00:00'
  }
}

// Generic function to process weather data, adjust based on actual API response format
function processWeatherData(data: unknown): WeatherData {
  const fallback = createFallbackWeatherData()
  if (!isObject(data)) {
    return fallback
  }

  // Try multiple common data formats
  const dataObj = data

  // Format 1: { current: { temp: 12, ... }, hourly: [...] }
  const current = asObject(dataObj.current)
  if (current) {
    const tempRaw = pickNumber(current.temp, current.temperature, current.temp_c) ?? 12
    const temp = toCelsius(tempRaw)

    const { code: weatherCode, description } = extractWeatherMeta(current)

    // Process hourly forecast
    let forecast: WeatherData['forecast'] = []
    if (Array.isArray(dataObj.hourly)) {
      forecast = dataObj.hourly
        .slice(1, 6) // Skip the first element which is `current`, take the next 5 hours
        .map((item: unknown): WeatherData['forecast'][number] | null => {
          const hour = asObject(item)
          if (!hour) return null

          const hourTempRaw = pickNumber(hour.temp, hour.temperature, hour.temp_c) ?? 12
          const hourTemp = toCelsius(hourTempRaw)
          const hourTime = pickTime(hour.time, hour.dt, hour.datetime)
          const { code: hourCode, description: hourDesc } = extractWeatherMeta(hour)

          return {
            time: formatTime(hourTime),
            temperature: Math.round(hourTemp),
            icon: getWeatherIcon(hourCode, hourDesc),
          }
        })
        .filter((item): item is WeatherData['forecast'][number] => item !== null)
    } else if (Array.isArray(dataObj.forecast)) {
      forecast = dataObj.forecast
        .slice(0, 5)
        .map((item: unknown): WeatherData['forecast'][number] | null => {
          const hour = asObject(item)
          if (!hour) return null

          const hourTempRaw = pickNumber(hour.temp, hour.temperature, hour.temp_c) ?? 12
          const hourTemp = toCelsius(hourTempRaw)
          const hourTime = pickTime(hour.time, hour.dt, hour.datetime)
          const { code: hourCode, description: hourDesc } = extractWeatherMeta(hour)

          return {
            time: formatTime(hourTime),
            temperature: Math.round(hourTemp),
            icon: getWeatherIcon(hourCode, hourDesc),
          }
        })
        .filter((item): item is WeatherData['forecast'][number] => item !== null)
    }

    return {
      current: {
        temperature: Math.round(temp),
        icon: getWeatherIcon(weatherCode, description),
      },
      forecast: forecast.length > 0 ? forecast : fallback.forecast,
    }
  }

  // If format doesn't match, return default data
  return fallback
}

export default function Weather() {
  const [weatherData, setWeatherData] = useState<WeatherData | null>(null)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)

  // Fetch data when component loads since we no longer depend on geolocation
  const displayWeatherData = weatherData || createFallbackWeatherData()

  useEffect(() => {
    let cancelled = false
    Promise.resolve()
      .then(() => {
        if (cancelled) return null
        setLoading(true)
        setError(null)
        // No parameters needed
        return getWeatherAPI()
      })
      .then((data) => {
        if (cancelled || !data) return

        console.log('Weather API Response:', data)

        // Use generic processing logic, backend has already handled the format
        const processedData = processWeatherData(data)
        setWeatherData(processedData)
      })
      .catch((err) => {
        if (cancelled) return
        console.error('Weather API Error:', err)
        setError(err instanceof Error ? err.message : 'Failed to load weather data')
      })
      .finally(() => {
        if (!cancelled) setLoading(false)
      })

    return () => {
      cancelled = true
    }
  }, [])

  // Loading: don't show any numbers, only show loading style
  if (loading || !weatherData) {
    return (
      <div>
        <div className="rounded-xl p-3 mb-4 flex items-center justify-center border border-gray-200/60 min-h-[72px]">
          <div className="flex items-center gap-2 text-gray-400">
            <span className="inline-block h-5 w-5 animate-spin rounded-full border-2 border-gray-300 border-t-gray-500" />
            <span className="text-sm">Loading weather</span>
          </div>
        </div>
        <div>
          <div className="text-xs text-gray-400 mb-3 font-normal">Forecast</div>
          <div className="flex gap-1.5 justify-between">
            {[1, 2, 3, 4, 5].map((i) => (
              <div key={i} className="flex flex-col items-center gap-1.5 flex-1">
                <div className="h-3 w-8 rounded skeleton" />
                <div className="h-6 w-6 rounded-full skeleton" />
                <div className="h-3 w-7 rounded skeleton" />
              </div>
            ))}
          </div>
        </div>
      </div>
    )
  }

  return (
    <div>
      {error && (
        <div className="text-center text-red-500 text-xs mb-2">{error}</div>
      )}
      {/* Current weather section */}
      <div className="bg-white/60 rounded-xl p-3 mb-4 flex items-center justify-between border border-gray-200/60">
        <div className="text-4xl font-bold text-black leading-none">{displayWeatherData.current.temperature}°C</div>
        <div className="text-black">
          <WeatherIcon type={displayWeatherData.current.icon} />
        </div>
      </div>

      {/* Hourly forecast section */}
      <div>
        <div className="text-xs text-gray-500 mb-3 font-normal">Forecast</div>
        <div className="flex gap-1.5 justify-between">
          {displayWeatherData.forecast.map((item, index) => (
            <div key={index} className="flex flex-col items-center gap-1.5 flex-1">
              <div className="text-xs text-gray-500 font-normal">{item.time}</div>
              <div className="text-black flex items-center justify-center h-6">
                <WeatherIcon type={item.icon} />
              </div>
              <div className="text-xs font-bold text-black">{item.temperature}°C</div>
            </div>
          ))}
        </div>
      </div>
    </div>
  )
}
