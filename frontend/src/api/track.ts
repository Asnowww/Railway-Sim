import { apiBaseUrl } from './config'
import type { TrackSegmentState } from '../types/simulation'

async function request<T>(path: string): Promise<T> {
  const response = await fetch(`${apiBaseUrl}${path}`)
  if (!response.ok) {
    throw new Error(`Request failed: ${response.status} ${response.statusText}`)
  }
  return response.json() as Promise<T>
}

export const trackApi = {
  topology: () => request<Record<string, unknown>>('/infrastructure/topology'),
  segments: () => request<TrackSegmentState[]>('/track/segments'),
  segment: (segmentId: string) => request<TrackSegmentState>(`/track/segments/${encodeURIComponent(segmentId)}`),
  position: (mileage: number, direction = 'FORWARD') =>
    request<Record<string, unknown>>(`/track/position?mileage=${encodeURIComponent(String(mileage))}&direction=${encodeURIComponent(direction)}`),
  positions: () => request<unknown[]>('/track/positions')
}
