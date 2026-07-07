export type SimulationStatus = 'STOPPED' | 'RUNNING' | 'PAUSED'

export interface TrainState {
  id: string
  routeId: string
  positionMeters: number
  speedMetersPerSecond: number
  lengthMeters: number
  loadRate: number
  status: string
  operationMode: string
  accelerationMetersPerSecondSquared: number
  tractionForceNewtons: number
  brakeForceNewtons: number
  railCurrentAmps: number
  tractionPowerWatts: number
  regenPowerWatts: number
  energyConsumedKwh: number
  energyRegeneratedKwh: number
  faultCode: string
}

export interface TrackSegmentState {
  id: string
  startMeters: number
  endMeters: number
  speedLimitMetersPerSecond: number
  occupancy: 'FREE' | 'RESERVED' | 'OCCUPIED' | 'FAULT'
}

export interface MovementAuthority {
  trainId: string
  authorityEndMeters: number
  speedLimitMetersPerSecond: number
  reason: string
}

export type SignalAspect = 'RED' | 'YELLOW' | 'GREEN'

export interface SignalState {
  signalId: string
  segmentId: string
  positionMeters: number
  aspect: SignalAspect
  reasonTrainId: string | null
}

export interface PowerSectionState {
  id: string
  name: string
  startMeters: number
  endMeters: number
  voltage: number
  current: number
  status: string
}

export interface Alarm {
  id: string
  sourceModule: string
  locationRef: string
  level: number
  title: string
  detail: string
  raisedAt: string
  confirmed: boolean
}

export interface DispatchCommand {
  id: string
  trainId: string
  commandType: string
  detail: string | null
  createdAt: string
}

export interface SimulationSnapshot {
  tick: number
  simulatedTime: string
  status: SimulationStatus
  trains: TrainState[]
  trackSegments: TrackSegmentState[]
  authorities: MovementAuthority[]
  signalStates: SignalState[]
  powerSections: PowerSectionState[]
  alarms: Alarm[]
}

export interface SocketMessage {
  type: 'snapshot'
  payload: SimulationSnapshot
}
