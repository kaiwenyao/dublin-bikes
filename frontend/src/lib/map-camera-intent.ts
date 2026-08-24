export interface MapCameraIntent {
  beginRecenterRequest: () => number
  completeRecenterRequest: (requestId: number) => void
  cancelPendingRecenter: () => void
  consumePendingRecenter: () => boolean
}

/**
 * Coordinates asynchronous location requests with direct map gestures.
 *
 * A request may recenter the map only when no newer request or user gesture
 * happened before its result was applied. Consuming the intent makes the
 * recenter one-shot, so unrelated renders cannot reset the camera later.
 */
export function createMapCameraIntent(): MapCameraIntent {
  let latestActionId = 0
  let pendingRecenter = false

  return {
    beginRecenterRequest() {
      pendingRecenter = false
      latestActionId += 1
      return latestActionId
    },

    completeRecenterRequest(requestId) {
      if (requestId === latestActionId) {
        pendingRecenter = true
      }
    },

    cancelPendingRecenter() {
      pendingRecenter = false
      latestActionId += 1
    },

    consumePendingRecenter() {
      if (!pendingRecenter) return false
      pendingRecenter = false
      return true
    },
  }
}
