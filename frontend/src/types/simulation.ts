import type { DispatchSnapshot } from './dispatch'

export type SimulationStatus = 'STOPPED' | 'RUNNING' | 'PAUSED' | string

export interface TrainState {
  id: string
  routeId: string
  serviceNo: string
  controlSessionState: string
  signalNetworkStatus: string
  powerNetworkStatus: string
  controlSessionReason: string
  linkId: number
  direction: string
  positionMeters: number
  speedMetersPerSecond: number
  lengthMeters: number
  headMileage: number
  tailMileage: number
  loadRate: number
  loadMassKg: number
  overloadStatus: string
  availableTractionCount: number
  availableBrakeCount: number
  vehicleProtectionReason: string
  status: string
  operationMode: string
  zeroSpeed: boolean
  doorState: string
  tractionState: string
  brakeState: string
  currentCollectionStatus: string
  tractionAvailable: boolean
  brakeAvailable: boolean
  selfCheckStatus: string
  faultLevel: number
  availableOperationMode: string
  dataQuality: string
  dynamicsState: string
  dynamicsConstraintReason: string
  speedLimitMetersPerSecond: number
  vehicleFaultSpeedLimitMetersPerSecond: number
  movementAuthorityDistanceMeters: number
  stationDistanceMeters: number
  stoppingDistanceMeters: number
  accelerationMetersPerSecondSquared: number
  tractionForceNewtons: number
  brakeForceNewtons: number
  regenBrakeForceNewtons: number
  railCurrentAmps: number
  tractionPowerWatts: number
  regenPowerWatts: number
  energyConsumedKwh: number
  energyRegeneratedKwh: number
  faultCode: string
  currentStationId?: string | null
  dwellElapsedSeconds?: number
  lastDepartureAt?: string | null
}

export interface TrackSegmentState {
  id: string
  startMeters: number
  endMeters: number
  speedLimitMetersPerSecond: number
  occupancy: string
  fromNode: string
  toNode: string
  track: string
}

export interface MovementAuthority {
  trainId: string
  authorityEndMeters: number
  speedLimitMetersPerSecond: number
  reason: string
  currentSegmentId: string
}

export interface SignalState {
  signalId: string
  segmentId: string
  positionMeters: number
  aspect: string
  reasonTrainId?: string | null
}

export interface SwitchState {
  id: string
  nodeId: string
  position: string
  locked: boolean
  activeSegmentId: string
}

export interface RouteState {
  routeId: string
  status: string
  lockedSwitchIds: string[]
  establishedByTrainId?: string | null
  axleSegmentIds: string[]
}

export interface PowerSectionState {
  id: string
  name: string
  substationId: string
  feederId: string
  startMeters: number
  endMeters: number
  voltage: number
  current: number
  status: string
  loadWatts: number
  regenPowerWatts: number
  absorbedRegenPowerWatts: number
  unabsorbedRegenPowerWatts: number
  availablePowerWatts: number
  supplyMode: string
  isolatorStatus: string
  substationAvailability: string
  breakerStatus: string
  protectionState: string
  maintenanceState: string
  lockoutState: string
  externalDataQuality: string
  externalVoltage: number
  externalCurrent: number
  externalLoadWatts: number
  voltageDeviation: number
  voltageDeviationPercent: number
  voltageComparisonStatus: string
  externalSupportReason: string
  strayCurrentRiskLevel: string
  strayCurrentRiskReason: string
  affectedTrainIds: string[]
  dataQuality: string
  updatedAt: string
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

export interface VehicleRuntimeHealth {
  heartbeatStatus: string
  latencyMillis: number
  [key: string]: unknown
}

export interface SimulationSnapshot {
  tick: number
  simulatedTime: string
  status: SimulationStatus
  trains: TrainState[]
  trackSegments: TrackSegmentState[]
  authorities: MovementAuthority[]
  signalStates: SignalState[]
  switchStates: SwitchState[]
  routeStates: RouteState[]
  powerSections: PowerSectionState[]
  vehicleRuntime: VehicleRuntimeHealth
  alarms: Alarm[]
  dispatch: DispatchSnapshot
}

export interface SocketMessage {
  type: 'snapshot' | string
  payload: SimulationSnapshot
}
