export type SimulationStatus = 'STOPPED' | 'RUNNING' | 'PAUSED'

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
}

export interface TrackSegmentState {
  id: string
  startMeters: number
  endMeters: number
  speedLimitMetersPerSecond: number
  occupancy: 'FREE' | 'RESERVED' | 'OCCUPIED' | 'FAULT'
  fromNode: string
  toNode: string
  track: string
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

export type SwitchPosition = 'NORMAL' | 'REVERSE'

export interface SwitchState {
  id: string
  nodeId: string
  position: SwitchPosition
  locked: boolean
  activeSegmentId: string
}

export type RouteStatus = 'AVAILABLE' | 'ESTABLISHED' | 'CONFLICTED'

export interface RouteState {
  routeId: string
  status: RouteStatus
  lockedSwitchIds: string[]
  establishedByTrainId: string | null
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
  breakerStatus: string
  protectionState: string
  maintenanceState: string
  lockoutState: string
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
  switchStates: SwitchState[]
  routeStates: RouteState[]
  powerSections: PowerSectionState[]
  alarms: Alarm[]
}

export interface SocketMessage {
  type: 'snapshot'
  payload: SimulationSnapshot
}
