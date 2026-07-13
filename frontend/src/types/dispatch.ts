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
  commandId?: string | null
}

export interface OperationRouteTemplate {
  routeId: string
  name: string
  typeCode: string
  pointIds: string[]
  stationIds: string[]
  segmentIds: string[]
}

export interface OperationRouteCandidate {
  key: string
  routeId: string
  routeName: string
  direction: 'UP' | 'DOWN' | string
  pointIds: string[]
  stationIds: string[]
  segmentIds: string[]
}

export interface OperationPlanRequest {
  pointIds: string[]
  routeId?: string
  candidateKey?: string
  trainId?: string
  plannedDepartureAt?: string
  leadSeconds?: number
  headwaySeconds?: number
  priority?: number
}

export interface OperationPlanView {
  planId: string
  routeId: string
  routeName: string
  direction: 'UP' | 'DOWN' | string
  trainId: string
  originPointId: string
  destinationPointId: string
  viaPointIds: string[]
  pointIds: string[]
  stationIds: string[]
  segmentIds: string[]
  plannedDepartureAt: string
  status: string
  priority: number
  version: number
  routeCommandId?: string | null
  rejectReason?: string | null
}

export interface DispatchRouteDecision {
  decisionId: string
  selectedTrainId: string
  selectedRouteId: string
  waitingTrainIds: string[]
  priorityScores: Record<string, number>
  waitingSeconds: number
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
  failureCode: string | null
  failureCategory: string | null
  retryable: boolean
  retryCount: number
  expiresAt: string | null
  nextRetryAt: string | null
  timedOutAt: string | null
  cancelCommandId: string | null
}

export interface DispatchServicePlanView {
  serviceId: string
  circulationId: string
  trainId: string
  originStationId: string | null
  terminusStationId: string | null
  plannedDepartureAt: string | null
  departureStatus: string
  departureCommandId: string | null
}

export interface DispatchStationHeadway {
  stationId: string
  direction: string
  trainId: string
  frontTrainId: string
  departureAt: string
  targetHeadwaySeconds: number
  actualHeadwaySeconds: number
  headwayErrorSeconds: number
  state: string
  regulationAction: string
}

export interface DispatchSnapshot {
  runMode: string
  planId: string
  targetHeadwaySeconds: number
  defaultDwellSeconds: number
  services: DispatchServicePlanView[]
  stationHeadways: DispatchStationHeadway[]
  interventionActive: boolean
  trainProfiles: DispatchTrainProfile[]
  openDisturbances: DispatchDisturbance[]
  activeCommands: DispatchCommandView[]
  routeDispatchActive: boolean
  routeDecisions: DispatchRouteDecision[]
  routeReservations: DispatchRouteReservation[]
  operationPlans: OperationPlanView[]
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
  circulations: CirculationPlan[]
  services: TrainServicePlan[]
}

export interface CirculationPlan {
  id: string
  rollingStockId: string
  serviceIds: string[]
}

export interface PlannedStop {
  stationId: string
  arrivalOffsetSec: number
  departureOffsetSec: number
}

export interface TrainServicePlan {
  serviceId: string
  circulationId: string
  trainId: string
  trainNo: number
  linkId: number
  offsetMeters: number
  direction: string
  stops: PlannedStop[]
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
