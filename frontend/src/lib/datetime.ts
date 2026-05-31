/**
 * Backend/scraper store naive UTC datetimes (no "Z" suffix).
 * Parse as UTC, then format with the browser's local timezone.
 */

const HAS_TIMEZONE_SUFFIX = /(?:Z|[+-]\d{2}:\d{2})$/i

export function parseBackendUtcDateTime(value: string | number): Date {
  if (typeof value === 'number') {
    return new Date(value)
  }

  const trimmed = String(value).trim()
  if (/^\d+$/.test(trimmed)) {
    return new Date(Number(trimmed))
  }

  const normalized = trimmed.replace(' ', 'T')
  const withZone = HAS_TIMEZONE_SUFFIX.test(normalized) ? normalized : `${normalized}Z`
  return new Date(withZone)
}

/** Chart X-axis label in the user's local timezone */
export function formatChartAxisTime(date: Date): string {
  return date.toLocaleTimeString(undefined, {
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
  })
}

/** Chart tooltip label in the user's local timezone */
export function formatChartTooltipTime(date: Date): string {
  return date.toLocaleString(undefined, {
    year: 'numeric',
    month: 'short',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
  })
}
