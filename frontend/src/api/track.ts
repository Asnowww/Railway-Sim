import { request } from '../shared/api/client'
import type { TrackSegmentState } from '../types/simulation'
import type { LineTopology } from '../types/topology'

export interface TrainTrackPosition {
  trainId?: string
  segmentId?: string
  mileage?: number
  offsetMeters?: number
  track?: string
  [key: string]: unknown
}

export const trackApi = {
  topology: () => request<LineTopology>('/infrastructure/topology'),
  segments: () => request<TrackSegmentState[]>('/track/segments'),
  segment: (segmentId: string) => request<TrackSegmentState>(`/track/segments/${encodeURIComponent(segmentId)}`),
  position: (mileage: number, direction = 'FORWARD') =>
    request<Record<string, unknown>>(
      `/track/position?mileage=${encodeURIComponent(String(mileage))}&direction=${encodeURIComponent(direction)}`
    ),
  positions: () => request<TrainTrackPosition[]>('/track/positions')
}
