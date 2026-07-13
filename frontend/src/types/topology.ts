import type { TrackSegmentState } from './simulation'

/** GET /api/infrastructure/topology —— 态势图几何的唯一来源。 */
export interface TopologyStation {
  id: string
  name: string
  centerMeters: number
  [key: string]: unknown
}

export interface TopologySwitchInfo {
  id: string
  nodeId?: string
  [key: string]: unknown
}

export interface TopologyRouteInfo {
  routeId?: string
  id?: string
  name?: string
  segmentIds?: string[]
  [key: string]: unknown
}

export interface LineTopology {
  lineId: string
  lineName?: string
  lengthMeters: number
  stations: TopologyStation[]
  segments: TrackSegmentState[]
  switches?: TopologySwitchInfo[]
  routes?: TopologyRouteInfo[]
  forwardNeighbors?: Record<string, string[]>
  [key: string]: unknown
}
