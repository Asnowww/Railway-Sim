export interface DispatchTrainProfile {
  trainId: string
  frontTrainId: string | null
  headwayActualSeconds: number | null
  headwayDeviationSeconds: number
  headwayState: string
  headwayAction: string
  dwellDeviationSeconds: number
  departureDelaySeconds: number
}

export interface DispatchDisturbance {
  id: string
  trainId: string
  stationId: string
  disturbanceType: string
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
  commandType: string
  status: string
  reason: string
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
  rejectReason?: string
}

export interface DispatchRouteDecisionView {
  decisionId: string
  selectedTrainId: string
  selectedRouteId: string
  waitingTrainIds: string[]
  status: string
  routeCommandId: string
  reason: string
  rejectReason?: string | null
}

export interface DispatchRouteReservationView {
  reservationId: string
  trainId: string
  routeId: string
  decisionId: string
  state: string
  commandId: string
  rejectReason?: string | null
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
  routeDecisions: DispatchRouteDecisionView[]
  routeReservations: DispatchRouteReservationView[]
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
