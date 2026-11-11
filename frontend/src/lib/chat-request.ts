/**
 * Ownership rules for the chat request lifecycle.
 *
 * A send is not finished when its promise settles: it may still be waiting for
 * geolocation, and the user can stop it and start another send in between. Only
 * the request that still owns the active controller may clear the page-wide
 * sending state; a superseded request may only release its own buffer — never
 * the buffer of the newer request that is already streaming.
 */
export type RequestRelease = 'active' | 'superseded'

export interface CancelableBuffer {
  cancel(): void
}

export function releaseRequest(
  activeController: AbortController | null,
  requestController: AbortController,
  ownBatcher: CancelableBuffer | null
): RequestRelease {
  if (activeController === requestController) {
    return 'active'
  }
  ownBatcher?.cancel()
  return 'superseded'
}
