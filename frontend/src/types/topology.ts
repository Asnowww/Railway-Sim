import type { TrackSegmentState } from './simulation'

/** GET /api/infrastructure/topology —— 态势图几何的唯一来源。 */

/** 双向站台（9号线：上下行站中心/停车窗口/站台侧不同） */
export interface TopologyPlatform {
  id: string
  /** 股道标签：up / down / main */
  track: string
  centerMeters: number
  stopLeftMeters: number
  stopRightMeters: number
  side?: string | null
  anchorSegmentId?: string | null
}

export interface TopologyStation {
  id: string
  name: string
  centerMeters: number
  platforms?: TopologyPlatform[]
  [key: string]: unknown
}

/** topology 端点输出的区段（快照 TrackSegmentState + 视景边号） */
export interface TopologySegment extends TrackSegmentState {
  /** 视景系统 UDP 边号（raw_segment_id），无则未配置 */
  rawSegmentId?: number
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
  segments: TopologySegment[]
  switches?: TopologySwitchInfo[]
  routes?: TopologyRouteInfo[]
  forwardNeighbors?: Record<string, string[]>
  [key: string]: unknown
}
