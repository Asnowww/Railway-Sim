/** 车辆域 REST 明细类型。 */

export interface TrainFaultRecord {
  id?: string | number
  trainId?: string
  faultType?: string
  faultCode?: string
  reason?: string
  operator?: string
  traceId?: string
  injectedAt?: string
  clearedAt?: string | null
  active?: boolean
  [key: string]: unknown
}

export interface VehicleMaintenanceState {
  trainId: string
  serviceNo?: string
  maintenanceState?: string
  faultCode?: string
  faultLevel?: number
  selfCheckStatus?: string
  availableOperationMode?: string
  dataQuality?: string
  timestamp?: string
  [key: string]: unknown
}

export interface DriverConsoleState {
  doorModeSwitchState?: string
  atoStartFlag?: boolean
  modeUpgradeConfirmFlag?: boolean
  modeDowngradeConfirmFlag?: boolean
  automaticTurnbackFlag?: boolean
  directionHandleState?: string
  masterHandleState?: string
  [key: string]: unknown
}

export interface VehicleSignalStatus {
  trainId?: string
  trainNo?: number
  zeroSpeed?: boolean
  doorState?: string
  brakeAvailable?: boolean
  tractionAvailable?: boolean
  faultLevel?: number
  driverConsoleState?: DriverConsoleState | null
  [key: string]: unknown
}

export interface DoorEnableState {
  side?: string
  automaticOpenAllowed?: boolean
  manualOpenAllowed?: boolean
}

export interface CabDisplay {
  trainId?: string
  currentDrivingMode?: string
  maximumAvailableDrivingMode?: string
  /** 后端为对象 DoorEnableState，不是布尔 */
  doorEnable?: DoorEnableState | null
  doorControlMode?: string
  tractionBrakeInfo?: string
  departureInfo?: string
  turnbackInfo?: string
  speedLimitMetersPerSecond?: number
  emergencyBrake?: boolean
  distanceToNextStationMeters?: number
  [key: string]: unknown
}

export interface SignalVehicleCommand {
  trainId?: string
  trainNo?: number
  authorityEndMeters?: number
  speedLimitMetersPerSecond?: number
  /** 真实字段名（此前误用 tractionCut/serviceBrake/emergencyBrake） */
  tractionCutoff?: boolean
  serviceBrakeCommand?: boolean
  emergencyBrakeCommand?: boolean
  reason?: string
  cabDisplay?: CabDisplay | null
  [key: string]: unknown
}

export interface DriverCommandAcceptance {
  accepted: boolean
  commandId?: string
  trainId?: string
  reasonCode?: string
  receivedAt?: string
  expiresAt?: string
  [key: string]: unknown
}
