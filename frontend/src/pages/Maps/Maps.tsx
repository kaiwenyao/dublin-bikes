/// <reference types="@types/google.maps" />
import { useEffect, useRef, useState, useMemo } from 'react'
import { AreaChart, Area, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer } from 'recharts'
import { getStationsAPI, getStationAvailabilityAPI, getStationsStatusAPI, getStationPredictionAPI, type StationVO, type ChartData as PredictionChartData, type StationAvailabilityVO } from '@/api/station'
import { planJourneyAPI, type JourneyPlanResponse } from '@/api/journey'
import Weather from '@/components/Weather'


// ========== Google Maps API Related Constants ==========
/** Global callback name for Google Maps script to call after loading */
const GOOGLE_MAPS_CALLBACK = '__googleMapsCallback'
const DEFAULT_CENTER = '40.12150192260742,-100.45039367675781'
const DEFAULT_ZOOM = 4
const USER_ZOOM = 15
const DEMO_MAP_ID = 'DEMO_MAP_ID'
const MARKER_BASE_Z_INDEX = 1
const MARKER_ACTIVE_Z_INDEX = 10_000

// Helper to safely format update time
const formatUpdateTime = (val: string | number) => {
  if (!val) return '--'

  const num = Number(val)
  // Fix Safari browser not understanding SQL format "YYYY-MM-DD HH:mm:ss", replace space with standard "T"
  const safeString = typeof val === 'string' ? val.replace(' ', 'T') : val

  // If pure number (or numeric string) use num, otherwise use safeString for Date parsing
  const dateObj = !isNaN(num) ? new Date(num) : new Date(safeString)

  // If parsed time is invalid, show --
  if (isNaN(dateObj.getTime())) return '--'

  return dateObj.toLocaleTimeString('en-US', { hour: '2-digit', minute: '2-digit' })
}

declare global {
  interface Window {
    [GOOGLE_MAPS_CALLBACK]?: () => void
  }
}

function formatLocationError(code: number): string {
  switch (code) {
    case 1:
      return 'Location access denied. Please enable it in your browser settings.'
    case 2:
      return 'Unable to get location information.'
    case 3:
      return 'Location timed out. Please try again.'
    default:
      return 'Location failed. Please try again.'
  }
}

export default function Maps() {
  const mapContainerRef = useRef<HTMLDivElement>(null)

  const [scriptLoaded, setScriptLoaded] = useState(false)
  const [loadError, setLoadError] = useState<string | null>(null)
  const [userPosition, setUserPosition] = useState<{ lat: number; lng: number } | null>(null)
  const [locationLoading, setLocationLoading] = useState(false)
  const [locationError, setLocationError] = useState<string | null>(null)

  const [stations, setStations] = useState<StationVO[]>([])
  const [stationsLoading, setStationsLoading] = useState(true)
  const [stationsError, setStationsError] = useState<string | null>(null)
  const [panelOpen, setPanelOpen] = useState(true)
  const [stationsStatus, setStationsStatus] = useState<Record<number, StationAvailabilityVO>>({})

  const [selectedStation, setSelectedStation] = useState<StationVO | null>(null)
  const [stationDetail, setStationDetail] = useState<StationAvailabilityVO | null>(null)


  const [detailLoading, setDetailLoading] = useState(false)

  // ========== Prediction Chart Related State ==========

  // 1. Store "all" historical data returned by API (30 days)
  const [fullStationHistory, setFullStationHistory] = useState<StationAvailabilityVO[]>([])
  const [historyRange, setHistoryRange] = useState<'4h' | '1d'>('4h')

  const [timeRange, setTimeRange] = useState<'history' | 'future_4h' | 'future_24h'>('history')
  const [predictionData, setPredictionData] = useState<PredictionChartData[]>([])
  const [chartLoading, setChartLoading] = useState(false)

  // 2. Use useMemo to dynamically filter data (no need to re-call API when switching buttons)
  const filteredHistory = useMemo(() => {
    if (fullStationHistory.length === 0) return [];

    const now = Date.now();
    return fullStationHistory.filter(item => {
      // Parse time (compatible with numeric or string format)
      const num = Number(item.last_update);
      const itemTime = !isNaN(num) ? num : new Date(String(item.last_update).replace(' ', 'T')).getTime();
      
      const diffMs = now - itemTime;
      
      // Filter based on selected range
      if (historyRange === '4h') return diffMs <= 4 * 60 * 60 * 1000;
      return true;
    });
  }, [fullStationHistory, historyRange]);
  const displayedChartData: ReadonlyArray<StationAvailabilityVO | PredictionChartData> =
    timeRange === 'history' ? filteredHistory : predictionData

  // When selecting "Future Prediction", call backend API
  useEffect(() => {
    if (!selectedStation || timeRange === 'history') return
    
    let cancelled = false
    setChartLoading(true)
    
    const hours = timeRange === 'future_4h' ? 4 : 24
    
    getStationPredictionAPI(selectedStation.number, hours)
      .then(data => {
        if (!cancelled) setPredictionData(data)
      })
      .finally(() => {
        if (!cancelled) setChartLoading(false)
      })
      
    return () => { cancelled = true }
  }, [selectedStation, timeRange])


  // Journey Planner State
  const startInputRef = useRef<HTMLInputElement>(null)
  const endInputRef = useRef<HTMLInputElement>(null)
  const [startPoint, setStartPoint] = useState<{ lat: number; lng: number, address: string } | null>(null)
  const [endPoint, setEndPoint] = useState<{ lat: number; lng: number, address: string } | null>(null)
  const [journeyLoading, setJourneyLoading] = useState(false)
  const [journeyError, setJourneyError] = useState<string | null>(null)
  const [journeyResult, setJourneyResult] = useState<JourneyPlanResponse | null>(null)

  // References to Direction Renderers to clear them later
  const directionsRenderersRef = useRef<google.maps.DirectionsRenderer[]>([])
  // References for custom route markers
  const journeyMarkersRef = useRef<HTMLElement[]>([])



  const apiKey = import.meta.env.VITE_GOOGLE_MAPS_API_KEY
  const keyError =
    !apiKey || typeof apiKey !== 'string' || !apiKey.startsWith('AIza')
      ? 'Please set a valid VITE_GOOGLE_MAPS_API_KEY in .env'
      : null
  const error = keyError ?? loadError

  /**
   * [Call Google Maps API] Load Google Maps JavaScript SDK
   * - Request via dynamic <script> tag insertion: https://maps.googleapis.com/maps/api/js
   * - Use key, callback, libraries=maps,marker, v=beta and other parameters
   * - After successful loading, execute global callback, set scriptLoaded to true, triggering map render
   */
  useEffect(() => {
    if (keyError) return

    if (document.querySelector('script[src*="maps.googleapis.com"]')) {
      queueMicrotask(() => setScriptLoaded(true))
      return
    }

    window[GOOGLE_MAPS_CALLBACK] = () => setScriptLoaded(true)

    const script = document.createElement('script')
    script.src = `https://maps.googleapis.com/maps/api/js?key=${apiKey}&callback=${GOOGLE_MAPS_CALLBACK}&libraries=maps,marker,places,routes&language=en&v=beta`
    script.async = true
    script.onerror = () => setLoadError('Google Maps script failed to load')
    document.head.appendChild(script)

    return () => {
      script.remove()
      delete window[GOOGLE_MAPS_CALLBACK]
    }
  }, [apiKey, keyError])

  useEffect(() => {
    let cancelled = false
    queueMicrotask(() => {
      setStationsLoading(true)
      setStationsError(null)
    })
    
    // Send two API requests simultaneously
    Promise.all([getStationsAPI(), getStationsStatusAPI()])
      .then(([stationsData, statusData]) => {
        if (!cancelled) {
          setStations(stationsData)
          
          // Convert status array to Object dictionary: { station_number: status_data }
          const statusMap: Record<number, StationAvailabilityVO> = {}
          statusData.forEach(st => {
            statusMap[st.number] = st
          })
          setStationsStatus(statusMap)
        }
      })
      .catch((err) => {
        if (!cancelled) setStationsError(err instanceof Error ? err.message : 'Failed to load stations')
      })
      .finally(() => {
        if (!cancelled) setStationsLoading(false)
      })
      
    return () => {
      cancelled = true
    }
  }, [])

  /** Automatically locate to user's position when entering page */
  useEffect(() => {
    if (!navigator.geolocation) return
    queueMicrotask(() => {
      setLocationError(null)
      setLocationLoading(true)
    })
    navigator.geolocation.getCurrentPosition(
      (position) => {
        setUserPosition({
          lat: position.coords.latitude,
          lng: position.coords.longitude,
        })
        setLocationLoading(false)
      },
      (err) => {
        setLocationError(formatLocationError(err.code))
        setLocationLoading(false)
      },
      { enableHighAccuracy: true, timeout: 10000, maximumAge: 0 }
    )
  }, [])

  /**
   * [Map Rendering] Create and mount map when Google Maps SDK loaded and container exists
   * Rendering flow:
   * 1. Use Google's Web Component: document.createElement('gmp-map'), the <gmp-map> registered by SDK internally calls Maps JavaScript API to render map tiles
   * 2. Set center, zoom, map-id and other properties to determine map center and zoom level
   * 3. Create gmp-advanced-marker markers (user position + stations), append to gmp-map
   * 4. Mount gmpMap to div corresponding to ref (mapContainerRef), map displays on page
   */
  useEffect(() => {
    if (!scriptLoaded || !mapContainerRef.current) return

    const container = mapContainerRef.current
    container.innerHTML = ''

    // Setup Autocomplete
    if (typeof google !== 'undefined' && google.maps && google.maps.places) {
      if (startInputRef.current && !startInputRef.current.dataset.hasAutocomplete) {
        const startAutocomplete = new google.maps.places.Autocomplete(startInputRef.current)
        startAutocomplete.addListener('place_changed', () => {
          const place = startAutocomplete.getPlace()
          if (place.geometry && place.geometry.location) {
            setStartPoint({
              lat: place.geometry.location.lat(),
              lng: place.geometry.location.lng(),
              address: place.formatted_address || place.name || ''
            })
          }
        })
        startInputRef.current.dataset.hasAutocomplete = 'true'
      }
      if (endInputRef.current && !endInputRef.current.dataset.hasAutocomplete) {
        const endAutocomplete = new google.maps.places.Autocomplete(endInputRef.current)
        endAutocomplete.addListener('place_changed', () => {
          const place = endAutocomplete.getPlace()
          if (place.geometry && place.geometry.location) {
            setEndPoint({
              lat: place.geometry.location.lat(),
              lng: place.geometry.location.lng(),
              address: place.formatted_address || place.name || ''
            })
          }
        })
        endInputRef.current.dataset.hasAutocomplete = 'true'
      }
    }

    const center = userPosition
      ? `${userPosition.lat},${userPosition.lng}`
      : DEFAULT_CENTER
    const zoom = userPosition ? USER_ZOOM : DEFAULT_ZOOM

    /* Create map root node: <gmp-map> registered by Google Maps JS SDK, mounted and rendered by SDK */
    const gmpMap = document.createElement('gmp-map')
    gmpMap.setAttribute('center', center)
    gmpMap.setAttribute('zoom', String(zoom))
    gmpMap.setAttribute('map-id', DEMO_MAP_ID)
    gmpMap.style.height = '100%'
    gmpMap.style.width = '100%'
    gmpMap.style.minHeight = '0'

    let activeTooltip: HTMLDivElement | null = null
    let activeMarker: HTMLElement | null = null
    let hideTimeoutId: ReturnType<typeof setTimeout> | null = null

    const setMarkerZIndex = (marker: HTMLElement, zIndex: number) => {
      marker.setAttribute('z-index', String(zIndex))
      marker.style.zIndex = String(zIndex)
        ; (marker as HTMLElement & { zIndex?: number }).zIndex = zIndex
    }

    const clearHideTimeout = () => {
      if (hideTimeoutId) {
        clearTimeout(hideTimeoutId)
        hideTimeoutId = null
      }
    }

    const showTooltip = (marker: HTMLElement, tooltip: HTMLDivElement) => {
      clearHideTimeout()
      if (activeTooltip && activeTooltip !== tooltip) {
        activeTooltip.classList.add('hidden')
      }
      if (activeMarker && activeMarker !== marker) {
        setMarkerZIndex(activeMarker, MARKER_BASE_Z_INDEX)
      }
      tooltip.classList.remove('hidden')
      setMarkerZIndex(marker, MARKER_ACTIVE_Z_INDEX)
      activeTooltip = tooltip
      activeMarker = marker
    }

    /** Delayed hide, allows mouse to move from dot to tooltip without flickering */
    const hideTooltip = (marker: HTMLElement, tooltip: HTMLDivElement) => {
      clearHideTimeout()
      hideTimeoutId = setTimeout(() => {
        tooltip.classList.add('hidden')
        setMarkerZIndex(marker, MARKER_BASE_Z_INDEX)
        if (activeTooltip === tooltip) activeTooltip = null
        if (activeMarker === marker) activeMarker = null
        hideTimeoutId = null
      }, 150)
    }

    /** Hide immediately (click toggle / click map blank area) */
    const hideTooltipNow = (marker: HTMLElement, tooltip: HTMLDivElement) => {
      clearHideTimeout()
      tooltip.classList.add('hidden')
      setMarkerZIndex(marker, MARKER_BASE_Z_INDEX)
      if (activeTooltip === tooltip) activeTooltip = null
      if (activeMarker === marker) activeMarker = null
    }

    /* User location marker: <gmp-advanced-marker> also provided by Google Maps SDK, used to display points on map */
    if (userPosition) {
      const userMarker = document.createElement('gmp-advanced-marker')
      userMarker.setAttribute('position', center)
      userMarker.setAttribute('title', 'My Location')
      gmpMap.appendChild(userMarker)
    }

    /* Station markers: one gmp-advanced-marker per station, with custom content (icon + tooltip) */
    stations.forEach((s) => {
      const marker = document.createElement('gmp-advanced-marker')
      marker.setAttribute('position', `${s.latitude},${s.longitude}`)
      marker.setAttribute('title', s.name)
      marker.setAttribute('gmp-clickable', 'true')
      setMarkerZIndex(marker, MARKER_BASE_Z_INDEX)

      const markerContent = document.createElement('div')
      markerContent.className = 'relative flex items-center justify-center'
      markerContent.style.cursor = 'pointer'
      markerContent.tabIndex = 0
      markerContent.setAttribute('aria-label', s.name)

      const currentStatus = stationsStatus[s.number]

      let bgColorClass = 'bg-gray-400' // Default color: gray
      if (currentStatus) {
        if (currentStatus.available_bikes === 0) {
          bgColorClass = 'bg-red-500' // No bikes -> Red
        } else if (currentStatus.available_bikes <= 5) {
          bgColorClass = 'bg-yellow-400' // Running low (less than 5) -> Yellow
        } else {
          bgColorClass = 'bg-[#a3c661]' // More than 5 -> Green (using the nice theme green)
        }
      }

      const bikeIcon = document.createElement('div')
      bikeIcon.className = `relative z-10 flex h-7 w-7 items-center justify-center rounded-full border-2 border-white text-white shadow-sm ${bgColorClass}`

      bikeIcon.innerHTML = `
        <svg viewBox="0 0 80 80" fill="currentColor" stroke="currentColor" stroke-width="2" class="h-4 w-4">
          <path d="M63,34a16,16,0,0,0-3.19.32L57,23.87V16h8a1,1,0,0,0,0-2H56a1,1,0,0,0-1,1v8H31V20h5a1,1,0,0,0,0-2H24a1,1,0,0,0,0,2h5v3.76L23.25,35.27A16,16,0,1,0,33,51h7a1,1,0,0,0,.85-.48l14.79-24,2.25,8.35A16,16,0,1,0,63,34ZM17,64a14,14,0,0,1,0-28,13.84,13.84,0,0,1,5.35,1.07L16.11,49.55A1,1,0,0,0,17,51H31A14,14,0,0,1,17,64Zm1.62-15,5.51-11A14,14,0,0,1,31,49Zm20.82,0H33A16,16,0,0,0,25,36.18L30.62,25H54.21ZM63,64a14,14,0,0,1-4.59-27.21L62,50.26A1,1,0,0,0,63,51a1.15,1.15,0,0,0,.26,0A1,1,0,0,0,64,49.74L60.34,36.26A13.71,13.71,0,0,1,63,36a14,14,0,0,1,0,28Z"/>
        </svg>
      `

      const tooltip = document.createElement('div')
      tooltip.className =
        'hidden absolute bottom-[calc(100%+12px)] left-1/2 z-[99999] min-w-[240px] max-w-[280px] -translate-x-1/2 rounded-lg border border-border bg-background/95 px-4 py-3 text-left shadow-lg backdrop-blur'

      const title = document.createElement('div')
      title.className = 'font-medium text-foreground'
      title.textContent = s.name

      const address = document.createElement('div')
      address.className = 'mt-1 truncate text-xs text-muted-foreground'
      address.title = s.address
      address.textContent = s.address

      

      const bikesLine = document.createElement('div')
      bikesLine.className = 'mt-2 text-sm font-semibold text-[#a3c661]'
      bikesLine.textContent = currentStatus 
        ? `🚲 Bikes: ${currentStatus.available_bikes}` 
        : '🚲 Bikes: --'

      const standsLine = document.createElement('div')
      standsLine.className = 'mt-1 text-sm font-semibold text-[#f1c25f]'
      standsLine.textContent = currentStatus 
        ? `🅿️ Stands: ${currentStatus.available_bike_stands}` 
        : '🅿️ Stands: --'

      const arrow = document.createElement('div')
      arrow.className =
        'absolute left-1/2 top-full h-2.5 w-2.5 -translate-x-1/2 -translate-y-1/2 rotate-45 border-r border-b border-border bg-background/95'

      tooltip.append(title, bikesLine, standsLine, arrow)
      markerContent.append(bikeIcon, tooltip)

      const openStationInfo = () => showTooltip(marker, tooltip)
      const closeStationInfo = () => hideTooltip(marker, tooltip)
      const toggleStationInfo = () => {
        if (tooltip.classList.contains('hidden')) {
          showTooltip(marker, tooltip)
        } else {
          hideTooltipNow(marker, tooltip)
        }
      }

      markerContent.addEventListener('pointerenter', openStationInfo)
      markerContent.addEventListener('pointerleave', closeStationInfo)
      markerContent.addEventListener('focus', openStationInfo)
      markerContent.addEventListener('blur', closeStationInfo)
      markerContent.addEventListener('click', (event) => {
        event.stopPropagation()
        toggleStationInfo()

        setDetailLoading(true)
        setStationDetail(null)

        setTimeRange('history')
        setHistoryRange('4h')
        setPredictionData([])
        setChartLoading(false)

        setSelectedStation(s) // Update selected station
      })
      marker.addEventListener('gmp-click', () => {
        toggleStationInfo()

        setDetailLoading(true)
        setStationDetail(null)

        setTimeRange('history')
        setHistoryRange('4h')
        setPredictionData([])
        setChartLoading(false)

        setSelectedStation(s)
      })

      marker.appendChild(markerContent)
      gmpMap.appendChild(marker)
    })

    gmpMap.addEventListener('click', () => {
      if (activeTooltip && activeMarker) {
        hideTooltipNow(activeMarker, activeTooltip)
      }
    })

    /* Mount map root node to DOM: triggers <gmp-map> rendering here, base map drawn by Google Maps API */
    container.appendChild(gmpMap)

    /** Streamline Google default controls: via SDK exposed innerMap.setOptions keep only zoom, disable others */
    const mapEl = gmpMap as HTMLElement & {
      innerMap?: {
        setOptions: (opts: {
          disableDefaultUI?: boolean
          zoomControl?: boolean
          mapTypeControl?: boolean
          streetViewControl?: boolean
          fullscreenControl?: boolean
        }) => void
      }
    }
    let retries = 0
    const applyMapOptions = () => {
      if (mapEl.innerMap) {
        mapEl.innerMap.setOptions({
          disableDefaultUI: true,
          zoomControl: true,
          mapTypeControl: false,
          streetViewControl: false,
          fullscreenControl: false,
        })
        return
      }
      if (retries < 20) {
        retries += 1
        setTimeout(applyMapOptions, 50)
      }
    }
    setTimeout(applyMapOptions, 0)
  }, [scriptLoaded, userPosition, stations, stationsStatus])


  // When clicking station, fetch "complete" parking data and history from Flask backend
  useEffect(() => {
    if (!selectedStation) return
    let cancelled = false
    setDetailLoading(true)

    // Here we don't pass range parameter, directly get all
    getStationAvailabilityAPI(selectedStation.number)
      .then((data) => {
        if (!cancelled) {
          const parseTime = (val: number | string) => {
            const num = Number(val)
            return !isNaN(num) ? num : new Date(String(val).replace(' ', 'T')).getTime()
          }

          // 1. Sort data by timestamp from oldest to newest
          const sortedData = [...data].sort((a, b) => parseTime(a.last_update) - parseTime(b.last_update))

          // 2. Store sorted "complete data" into new State
          setFullStationHistory(sortedData)

          // 3. The last entry in the array is the newest data
          if (sortedData.length > 0) {
            setStationDetail(sortedData[sortedData.length - 1])
          } else {
            setStationDetail(null)
          }
        }
      })
      .catch((err) => {
        console.error('Failed to fetch station details:', err)
      })
      .finally(() => {
        if (!cancelled) setDetailLoading(false)
      })

    return () => {
      cancelled = true
    }
  }, [selectedStation])


  // Listen for journeyResult changes, draw route
  useEffect(() => {
    if (!scriptLoaded || !mapContainerRef.current) return
    const mapEl = mapContainerRef.current.querySelector('gmp-map') as HTMLElement & { innerMap?: google.maps.Map }
    if (!mapEl || !mapEl.innerMap || typeof google === 'undefined') return

    // Clear old routes before each draw
    directionsRenderersRef.current.forEach(renderer => renderer.setMap(null))
    directionsRenderersRef.current = []

    // Clear custom route markers
    journeyMarkersRef.current.forEach(marker => marker.remove())
    journeyMarkersRef.current = []

    if (!journeyResult) return

    const { route_info, search_context } = journeyResult
    const directionsService = new google.maps.DirectionsService()

    const drawRoute = (origin: google.maps.LatLngLiteral, destination: google.maps.LatLngLiteral, travelMode: string, polylineOptions: google.maps.PolylineOptions) => {
      const renderer = new google.maps.DirectionsRenderer({
        map: mapEl.innerMap,
        suppressMarkers: true,
        preserveViewport: true,
        polylineOptions
      })
      directionsRenderersRef.current.push(renderer)

      directionsService.route(
        {
          origin,
          destination,
          travelMode: travelMode as google.maps.TravelMode
        },
        (result, status) => {
          if (status === 'OK' && result) {
            renderer.setDirections(result)
          } else {
            console.error(`Directions request failed due to ${status}`)
          }
        }
      )
    }

    // First leg: Walking (start -> first station)
    drawRoute(
      { lat: search_context.start_resolved.lat, lng: search_context.start_resolved.lon },
      { lat: route_info.start_station.coords.lat, lng: route_info.start_station.coords.lon },
      'WALKING',
      {
        strokeOpacity: 0,
        icons: [{ icon: { path: 'M 0,-1 0,1', strokeOpacity: 1.0, scale: 3, strokeColor: '#003459', strokeWeight: 5 }, offset: '0', repeat: '16px' }]
      }
    )

    // Second leg: Biking (first station -> second station)
    drawRoute(
      { lat: route_info.start_station.coords.lat, lng: route_info.start_station.coords.lon },
      { lat: route_info.end_station.coords.lat, lng: route_info.end_station.coords.lon },
      'BICYCLING',
      { strokeColor: '#00A8E8', strokeOpacity: 1.0, strokeWeight: 8 }
    )

    // Third leg: Walking (second station -> end)
    drawRoute(
      { lat: route_info.end_station.coords.lat, lng: route_info.end_station.coords.lon },
      { lat: search_context.end_resolved.lat, lng: search_context.end_resolved.lon },
      'WALKING',
      {
        strokeOpacity: 0,
        icons: [{ icon: { path: 'M 0,-1 0,1', strokeOpacity: 1.0, scale: 3, strokeColor: '#003459', strokeWeight: 5 }, offset: '0', repeat: '16px' }]
      }
    )

    // Draw 4 custom key point markers
    const createMarker = (position: google.maps.LatLngLiteral, title: string, isStation: boolean) => {
      const marker = document.createElement('gmp-advanced-marker')
      marker.setAttribute('position', `${position.lat},${position.lng}`)
      marker.setAttribute('title', title)
      
      // 🌟 Key: Set super high z-index to overlay on top of dynamic stations that would change color
      marker.style.zIndex = '999999'

      const iconDiv = document.createElement('div')

      if (isStation) {
        // If it's a station in the journey: display green bike icon (directly overlay on original station)
        iconDiv.className = 'relative z-10 flex h-10 w-10 items-center justify-center rounded-full border-4 border-white bg-[#00A8E8] text-white shadow-[0_0_15px_rgba(0,0,0,0.3)]'
        iconDiv.innerHTML = `
          <svg viewBox="0 0 80 80" fill="currentColor" stroke="currentColor" stroke-width="2" class="h-6 w-6">
            <path d="M63,34a16,16,0,0,0-3.19.32L57,23.87V16h8a1,1,0,0,0,0-2H56a1,1,0,0,0-1,1v8H31V20h5a1,1,0,0,0,0-2H24a1,1,0,0,0,0,2h5v3.76L23.25,35.27A16,16,0,1,0,33,51h7a1,1,0,0,0,.85-.48l14.79-24,2.25,8.35A16,16,0,1,0,63,34ZM17,64a14,14,0,0,1,0-28,13.84,13.84,0,0,1,5.35,1.07L16.11,49.55A1,1,0,0,0,17,51H31A14,14,0,0,1,17,64Zm1.62-15,5.51-11A14,14,0,0,1,31,49Zm20.82,0H33A16,16,0,0,0,25,36.18L30.62,25H54.21ZM63,64a14,14,0,0,1-4.59-27.21L62,50.26A1,1,0,0,0,63,51a1.15,1.15,0,0,0,.26,0A1,1,0,0,0,64,49.74L60.34,36.26A13.71,13.71,0,0,1,63,36a14,14,0,0,1,0,28Z"/>
          </svg>
        `
      } else {
        // If pure start/end point: display small red dot
        iconDiv.className = 'h-5 w-5 rounded-full border-4 border-white bg-red-500 shadow-md'
      }

      marker.appendChild(iconDiv)
      mapEl.appendChild(marker)
      journeyMarkersRef.current.push(marker)
    }

    createMarker({ lat: search_context.start_resolved.lat, lng: search_context.start_resolved.lon }, 'Start', false)
    createMarker({ lat: route_info.start_station.coords.lat, lng: route_info.start_station.coords.lon }, route_info.start_station.name, true)
    createMarker({ lat: route_info.end_station.coords.lat, lng: route_info.end_station.coords.lon }, route_info.end_station.name, true)
    createMarker({ lat: search_context.end_resolved.lat, lng: search_context.end_resolved.lon }, 'End', false)

    // Zoom map to fit all route points
    const bounds = new google.maps.LatLngBounds()
    bounds.extend({ lat: search_context.start_resolved.lat, lng: search_context.start_resolved.lon })
    bounds.extend({ lat: route_info.start_station.coords.lat, lng: route_info.start_station.coords.lon })
    bounds.extend({ lat: route_info.end_station.coords.lat, lng: route_info.end_station.coords.lon })
    bounds.extend({ lat: search_context.end_resolved.lat, lng: search_context.end_resolved.lon })

    mapEl.innerMap.fitBounds(bounds, { top: 80, bottom: 80, left: 340, right: 80 })

  }, [journeyResult, scriptLoaded, stations])

  const handleLocate = () => {
    if (!navigator.geolocation) {
      setLocationError('Your browser does not support geolocation')
      return
    }
    setLocationError(null)
    setLocationLoading(true)
    navigator.geolocation.getCurrentPosition(
      (position) => {
        setUserPosition({
          lat: position.coords.latitude,
          lng: position.coords.longitude,
        })
        setLocationLoading(false)
      },
      (err) => {
        setLocationError(formatLocationError(err.code))
        setLocationLoading(false)
      },
      { enableHighAccuracy: true, timeout: 10000, maximumAge: 0 }
    )
  }

  const handlePlanJourney = async () => {
    if (!startPoint || !endPoint) {
      setJourneyError('Please select both Start and End points from the dropdown.')
      return
    }

    setJourneyLoading(true)
    setJourneyError(null)
    setJourneyResult(null)

    try {
      const response = await planJourneyAPI({
        start: { lat: startPoint.lat, lon: startPoint.lng },
        end: { lat: endPoint.lat, lon: endPoint.lng }
      })
      setJourneyResult(response.data)
    } catch (err: unknown) {
      if (err instanceof Error) {
        setJourneyError(err.message || 'Failed to plan journey.')
      } else {
        setJourneyError('Failed to plan journey.')
      }
    } finally {
      setJourneyLoading(false)
    }
  }

  return (
    <div className="relative w-full h-screen min-h-0">
      {/* Map fills the entire page */}
      <div className="absolute inset-0 z-0">
        {error ? (
          <div className="flex h-full items-center justify-center p-8">
            <div className="rounded-xl border border-amber-200 bg-amber-50 p-6 text-amber-800 text-sm max-w-md">
              {error}
            </div>
          </div>
        ) : (
          <div
            ref={mapContainerRef}
            className="h-full w-full bg-gray-100"
            style={{ minHeight: 0 }}
          />
        )}
      </div>

      {/* Left floating panel: station + location + weather combined */}
      <div
        className={`absolute top-24 left-4 bottom-10 z-20 w-72 pointer-events-auto rounded-2xl bg-white/90 backdrop-blur-sm shadow-lg p-4 flex flex-col overflow-visible transition-transform duration-300 ease-in-out ${panelOpen ? 'translate-x-0' : '-translate-x-[calc(100%+16px)]'}`}
      >
        {/* Panel toggle button — attached to panel right side */}
        <button
          type="button"
          onClick={() => setPanelOpen((v) => !v)}
          className="absolute -right-6 top-6 flex h-12 w-6 items-center justify-center rounded-r-lg rounded-l-none bg-white/90 backdrop-blur-sm shadow-[2px_1px_6px_-2px_rgba(0,0,0,0.12)] cursor-pointer"
        >
          <svg
            className={`h-4 w-4 text-gray-400 transition-transform duration-300 ${panelOpen ? '' : 'rotate-180'}`}
            viewBox="0 0 24 24"
            fill="none"
            stroke="currentColor"
            strokeWidth="2"
            strokeLinecap="round"
            strokeLinejoin="round"
          >
            <path d="M15 18l-6-6 6-6" />
          </svg>
        </button>

        {/* Station info */}
        <div className="flex items-center gap-2 mb-3">
          <span className="text-sm font-semibold text-foreground">Maps</span>
          {stationsLoading && (
            <span className="text-xs text-muted-foreground">Loading stations...</span>
          )}
          {!stationsLoading && stationsError && (
            <span className="text-xs text-amber-600">{stationsError}</span>
          )}
          {!stationsLoading && !stationsError && (
            <span className="text-xs text-muted-foreground">{stations.length} stations</span>
          )}
        </div>

        {/* Locate button */}
        <button
          type="button"
          onClick={handleLocate}
          disabled={locationLoading}
          className="w-full inline-flex items-center justify-center gap-2 rounded-xl bg-[#00A8E8] px-3 py-2 text-sm font-medium text-white shadow transition hover:bg-[#007EA7] disabled:opacity-60 disabled:cursor-not-allowed"
        >
          {locationLoading ? (
            <>
              <span className="inline-block h-3.5 w-3.5 animate-spin rounded-full border-2 border-white border-t-transparent" />
              Locating...
            </>
          ) : (
            <>
              <svg
                className="h-4 w-4"
                viewBox="0 0 24 24"
                fill="none"
                stroke="currentColor"
                strokeWidth="2"
                strokeLinecap="round"
                strokeLinejoin="round"
              >
                <path d="M20 10c0 6-8 12-8 12s-8-6-8-12a8 8 0 0 1 16 0Z" />
                <circle cx="12" cy="10" r="3" />
              </svg>
              Locate me
            </>
          )}
        </button>
        {userPosition && (
          <div className="mt-1.5 text-xs text-muted-foreground text-center">
            {userPosition.lat.toFixed(5)}, {userPosition.lng.toFixed(5)}
          </div>
        )}
        {locationError && (
          <div className="mt-1.5 text-xs text-amber-600 text-center">{locationError}</div>
        )}

        {/* Flexible spacer */}
        <div className="flex-1 min-h-0" />

        {/* Divider line */}
        <div className="my-3 border-t border-gray-200/60" />

        {/* Journey Planner */}
        <div className="mb-3">
          <div className="text-sm font-semibold text-foreground mb-2">Journey Planner</div>
          <div className="space-y-2">
            <input
              ref={startInputRef}
              type="text"
              placeholder="Start Point"
              className="w-full rounded-md border border-input bg-background px-3 py-1.5 text-sm shadow-sm transition-colors focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-[#00A8E8]"
              onChange={(e) => {
                if (!e.target.value) setStartPoint(null)
              }}
            />
            <input
              ref={endInputRef}
              type="text"
              placeholder="End Point"
              className="w-full rounded-md border border-input bg-background px-3 py-1.5 text-sm shadow-sm transition-colors focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-[#00A8E8]"
              onChange={(e) => {
                if (!e.target.value) setEndPoint(null)
              }}
            />
            <button
              type="button"
              onClick={handlePlanJourney}
              disabled={journeyLoading || !startPoint || !endPoint}
              className="w-full inline-flex items-center justify-center rounded-xl bg-[#00A8E8] px-3 py-2 text-sm font-medium text-white shadow transition hover:bg-[#007EA7] disabled:opacity-60 disabled:cursor-not-allowed"
            >
              {journeyLoading ? 'Planning...' : 'Plan Journey'}
            </button>
            {journeyError && <div className="text-xs text-amber-600 text-center">{journeyError}</div>}
            {journeyResult && (
              <div className="mt-2 text-xs border border-green-200 bg-green-50 p-2 rounded-md">
                <div className="font-semibold text-green-800">Journey Found</div>
                <div className="text-green-700 mt-1">Total Time: {Math.ceil(journeyResult.route_info.total_duration / 60)} mins</div>
                <div className="text-green-700 mt-1 truncate">Start Station: {journeyResult.route_info.start_station.name}</div>
                <div className="text-green-700 mt-1 truncate">End Station: {journeyResult.route_info.end_station.name}</div>
              </div>
            )}
          </div>
        </div>

        {/* Divider line */}
        <div className="mb-3 border-t border-gray-200/60" />

        {/* Weather */}
        <Weather />
      </div>


      {/* Station detail popup panel (centered display) */}
      {selectedStation && (
        <>
          {/* Semi-transparent black overlay, click to close */}
          <div
            className="absolute inset-0 z-30 bg-black/20 backdrop-blur-sm"
            onClick={() => setSelectedStation(null)}
          />

          {/* Centered white window */}
          <div className="absolute top-1/2 left-1/2 z-40 w-[90%] max-w-[460px] -translate-x-1/2 -translate-y-1/2 rounded-2xl bg-white p-6 shadow-2xl">

            {/* Top title and close button */}
            <div className="relative mb-6 flex items-center justify-center">
              <h2 className="text-xl font-bold text-gray-900 flex items-center gap-2">
                {selectedStation.name}
                {/* Status dot: loading (pulsing gray) -> no data (dark gray) -> no bikes (red) -> has bikes (green) */}
                <span
                  className={`h-3.5 w-3.5 rounded-full shadow-sm ${detailLoading
                    ? 'bg-gray-300 animate-pulse' // Loading: light gray + breathing animation
                      : !stationDetail
                        ? 'bg-gray-400'               // API failed or no data: dark gray
                        : stationDetail.available_bikes === 0
                          ? 'bg-red-500'                // Confirmed no bikes: red
                          : 'bg-[#a3c661]'              // Confirmed has bikes: green
                    }`}
                ></span>
              </h2>
              <button
                onClick={() => setSelectedStation(null)}
                className="absolute right-0 top-1/2 -translate-y-1/2 text-black hover:text-gray-600 transition-colors"
              >
                <svg className="h-7 w-7" viewBox="0 0 24 24" fill="currentColor">
                  <path fillRule="evenodd" d="M12 2.25c-5.385 0-9.75 4.365-9.75 9.75s4.365 9.75 9.75 9.75 9.75-4.365 9.75-9.75S17.385 2.25 12 2.25zm-1.72 6.97a.75.75 0 10-1.06 1.06L10.94 12l-1.72 1.72a.75.75 0 101.06 1.06L12 13.06l1.72 1.72a.75.75 0 101.06-1.06L13.06 12l1.72-1.72a.75.75 0 10-1.06-1.06L12 10.94l-1.72-1.72z" clipRule="evenodd" />
                </svg>
              </button>
            </div>

            {/* Data section */}
            <div className="mb-2 flex flex-col gap-3">
              {/* Bikes Available */}
              <div className="flex items-center rounded-xl border border-gray-400 py-3 px-5">
                <div className="text-3xl text-black">
                  <svg className="h-8 w-8" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                    <circle cx="5.5" cy="17.5" r="3.5" /><circle cx="18.5" cy="17.5" r="3.5" /><path d="M15 6a1 1 0 1 0 0-2 1 1 0 0 0 0 2zm-3 11.5V14l-3-3 4-3 2 3h2" />
                  </svg>
                </div>
                <div className="w-20 text-center text-[2.5rem] font-medium text-[#a3c661]">
                  {/* Dynamically display data, show -- when loading */}
                  {detailLoading ? '--' : (stationDetail?.available_bikes ?? '--')}
                </div>
                <div className="ml-4 text-xl text-gray-800">Bikes Available</div>
              </div>

              {/* Stands Free */}
              <div className="flex items-center rounded-xl border border-gray-400 py-3 px-5">
                <div className="text-[2.2rem] font-bold text-black pl-1.5 pr-1">P</div>
                <div className="w-20 text-center text-[2.5rem] font-medium text-[#f1c25f]">
                  {/* Dynamically display data, show -- when loading */}
                  {detailLoading ? '--' : (stationDetail?.available_bike_stands ?? '--')}
                </div>
                <div className="ml-4 text-xl text-gray-800">Stands Free</div>
              </div>
            </div>

            {/* Update time */}
            <div className="mb-4 text-right text-xs text-gray-400 pr-1">
              {stationDetail?.last_update
                ? `Updated: ${formatUpdateTime(stationDetail.last_update)}`
                : 'Updated: --'}
            </div>

            {/* History chart and prediction section */}
            <div className="overflow-hidden rounded-xl border border-gray-400">
              
              {/* === Top toggle section === */}
              <div className="flex flex-col bg-[#007EA7] text-white"> {/* 👈 Updated to theme blue */}
                {/* First row: History vs Prediction */}
                <div className="flex border-b border-white/20">
                  {/* Left: History record tab */}
                  <button 
                    className={`flex-1 py-1.5 text-center text-sm transition-colors ${
                      timeRange === 'history' 
                        ? 'bg-white text-gray-800 font-bold' 
                        : 'text-white hover:bg-black/10'     
                    }`}
                    onClick={() => setTimeRange('history')}
                  >
                    Historic Data
                  </button>
                  
                  {/* Right: Prediction model dropdown tab */}
                  <div 
                    className={`flex-1 relative flex items-center transition-colors ${
                      timeRange !== 'history' 
                        ? 'bg-white text-[#007EA7] font-bold' // Prediction keeps purple, maintaining futuristic feel
                        : 'text-white hover:bg-black/10'      
                    }`}
                  >
                    <select 
                      value={timeRange === 'history' ? 'placeholder' : timeRange} 
                      onChange={(e) => setTimeRange(e.target.value as 'future_4h' | 'future_24h')}
                      className="w-full h-full py-1.5 px-3 text-center text-sm bg-transparent appearance-none cursor-pointer focus:outline-none"
                    >
                      {timeRange === 'history' && (
                        <option value="placeholder" disabled hidden>Prediction Model 🔮</option>
                      )}
                      <option value="future_4h" className="text-gray-700 font-normal">Future 4 Hours 🔮</option>
                      <option value="future_24h" className="text-gray-700 font-normal">Future 24 Hours 🔮</option>
                    </select>
                    <span className="absolute right-3 pointer-events-none text-xs opacity-70">▼</span>
                  </div>
                </div>

                {/* Second row: History range toggle (only appears when History is selected) */}
                {timeRange === 'history' && (
                  <div className="flex text-xs bg-black/10">
                    {(['4h', '1d'] as const).map((range) => (
                      <button
                        key={range}
                        onClick={() => setHistoryRange(range)}
                        className={`flex-1 py-1.5 transition-colors border-r border-white/10 last:border-r-0 ${
                          historyRange === range 
                            ? 'bg-white text-[#007EA7] font-bold shadow-inner' // 👈 Active button text updated to theme blue
                            : 'hover:bg-white/10 text-white/80'
                        }`}
                      >
                        {range.toUpperCase()}
                      </button>
                    ))}
                  </div>
                )}
              </div>

              {/* === Chart render area === */}
              <div className="h-56 w-full bg-white p-2">
                {chartLoading ? (
                  <div className="flex h-full w-full items-center justify-center text-gray-400 text-sm">
                    <span className="inline-block h-4 w-4 animate-spin rounded-full border-2 border-[#007EA7] border-t-transparent mr-2" />
                    Loading AI Prediction...
                  </div>
                ) : displayedChartData.length > 0 ? (
                  <ResponsiveContainer width="100%" height="100%">
                    <AreaChart
                      data={displayedChartData}
                      margin={{ top: 10, right: 10, left: -25, bottom: 0 }}
                    >
                      <defs>
                        <linearGradient id="colorBikes" x1="0" y1="0" x2="0" y2="1">
                          {/* 👈 History chart gradient color updated to theme blue */}
                          <stop offset="5%" stopColor="#007EA7" stopOpacity={0.8} />
                          <stop offset="95%" stopColor="#007EA7" stopOpacity={0} />
                        </linearGradient>
                      </defs>
                      <CartesianGrid strokeDasharray="3 3" vertical={false} stroke="#e5e7eb" />

                      <XAxis
                        dataKey={timeRange === 'history' ? "requested_at" : "timeLabel"}
                        tickFormatter={(tick: string | number) => {
                          if (!tick) return '';
                          if (timeRange !== 'history') return tick as string; 
                          
                          const d = new Date(tick);
                          
                          return `${d.getHours()}:${d.getMinutes().toString().padStart(2, '0')}`;
                        }}
                        tick={{ fontSize: 12, fill: '#6b7280' }}
                        axisLine={false}
                        tickLine={false}
                        minTickGap={20} 
                      />

                      <YAxis
                        tick={{ fontSize: 12, fill: '#6b7280' }}
                        axisLine={false}
                        tickLine={false}
                        allowDecimals={false}
                      />

                      <Tooltip
                        labelFormatter={(label) => timeRange === 'history' ? new Date(label).toLocaleString('en-US') : `Predicted Time: ${label}`}
                        formatter={(value) => [value, timeRange === 'history' ? 'Available Bikes' : 'Predicted Bikes 🔮']}
                      />

                      <Area
                        type="monotone"
                        dataKey={timeRange === 'history' ? "available_bikes" : "bikes"}
                        stroke="#007EA7" 
                        strokeWidth={2}
                        strokeDasharray={timeRange === 'history' ? "0" : "5 5"}
                        fillOpacity={1}
                        fill="url(#colorBikes)"
                      />
                    </AreaChart>
                  </ResponsiveContainer>
                ) : (
                  <div className="flex h-full w-full items-center justify-center text-gray-400 text-sm">
                    {detailLoading ? 'Loading data...' : 'No data available'}
                  </div>
                )}
              </div>
            </div>
          </div>
        </>
      )}

      {/* Maps page specific: small fixed footer */}
      <footer className="fixed bottom-0 left-0 right-0 z-10 border-t border-border/50 bg-background/95 py-2 px-4 text-center text-xs text-muted-foreground backdrop-blur sm:px-6">
        © 2026 UCDSE. All rights reserved. Built with using React + Vite + Tailwind
      </footer>
    </div>
  )
}
