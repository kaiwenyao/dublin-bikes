const LOCATION_TERMS = [
  '附近',
  '最近',
  '离我',
  '我周围',
  '身边',
  'near me',
  'nearby',
  'nearest',
  'closest',
  'around me',
]

const BIKE_STATION_TERMS = [
  '车站',
  '站点',
  '单车',
  '自行车',
  'bike',
  'bikes',
  'station',
  'stations',
  'dock',
  'dublin bikes',
]

export function needsCurrentLocation(message: string): boolean {
  const normalized = message.trim().toLowerCase()
  if (!normalized) return false
  return (
    LOCATION_TERMS.some((term) => normalized.includes(term)) &&
    BIKE_STATION_TERMS.some((term) => normalized.includes(term))
  )
}
