export interface DispatchTrainProfile {
  trainId: string
  regulatedTrainId: string
  frontTrainId: string | null
  headwayActualSeconds: number | null
  headwayErrorSeconds: number | null
  headwayDeviationSeconds: number
  headwayState: string
  headwayAction: string
  regulationAction: 'CATCH_UP' | 'SLOW_DOWN' | 'NORMAL' | 'OBSERVE' | string
  regulationReason: string
  dwellDeviationSeconds: number
  departureDelaySeconds: number
}

export interface DispatchDisturbance {
  id: string
  trainId: string
  regulatedTrainId: string
  stationId: string
  disturbanceType: string
  regulationAction?: string | null
  deviationValue: number
  headwayDirection?: string | null
  targetHeadwaySec?: number | null
  actualHeadwaySec?: number | null
  toleranceSec?: number | null
  violationSec?: number | null
  status: string
}

export interface DispatchCommandView {
  id: string
  trainId: string
  regulatedTrainId?: string | null
  commandType: string
  status: string
  reason: string
  regulationAction?: string | null
  payload?: Record<string, unknown>
  createdAt?: string
  appliedAt?: string | null
}

export interface DispatchRouteInfo {
  routeId: string
  name: string
  type: string
  fromStation: string
  toStation: string
  segmentIds: string[]
  status: string
}

export interface DispatchRouteEstablishResponse {
  accepted: boolean
  routeId: string
  trainId: string
  rejectReason?: string | null
}

export interface DispatchRouteDecision {
  decisionId: string
  selectedTrainId: string
  selectedRouteId: string
  waitingTrainIds: string[]
  priorityScores: Record<string, number>
  status: string
  routeCommandId: string
  reason: string
  rejectReason: string | null
}

export interface DispatchRouteReservation {
  reservationId: string
  trainId: string
  routeId: string
  decisionId: string
  state: string
  commandId: string
  rejectReason: string | null
  retryCount: number
  expiresAt: string | null
}

export interface DispatchSnapshot {
  runMode: string
  planId: string
  targetHeadwaySeconds: number
  defaultDwellSeconds: number
  interventionActive: boolean
  trainProfiles: DispatchTrainProfile[]
  openDisturbances: DispatchDisturbance[]
  activeCommands: DispatchCommandView[]
  routeDispatchActive: boolean
  routeDecisions: DispatchRouteDecision[]
  routeReservations: DispatchRouteReservation[]
}

export interface RunPlanPeriod {
  periodType: string
  start: string
  end: string
  departureIntervalSec: number
  defaultDwellTimeSec: number
}

export interface RunPlanResponse {
  planId: string
  lineId: string
  periods: RunPlanPeriod[]
}

export interface TrainStationEvent {
  simulationRunId: string
  trainId: string
  lineId: string
  stationId: string
  eventType: string
  simulatedAt: string
  delaySeconds: number
}
