export interface DispatchTrainProfile {
  trainId: string
  headwayActualSeconds: number | null
  headwayDeviationSeconds: number
  dwellDeviationSeconds: number
}

export interface DispatchDisturbance {
  id: string
  trainId: string
  stationId: string
  disturbanceType: string
  deviationValue: number
  status: string
}

export interface DispatchCommandView {
  id: string
  trainId: string
  commandType: string
  status: string
  reason: string
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
