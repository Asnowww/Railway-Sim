import { postJson, request, requestBinary } from '../shared/api/client'
import type { VehicleRuntimeStatusResponse } from '../types/simulation'
import type { DriverCommandAcceptance, VehicleMaintenanceState } from '../types/vehicles'

export interface DriverCabPlcInputPacket {
  highVoltageClosedIndicator: boolean
  doorsClosedLockedIndicator: boolean
  networkFaultIndicator: boolean
  automaticTurnbackAvailable: boolean
  atoModeAvailable: boolean
  atoModeActive: boolean
  automaticTurnbackActive: boolean
  emergencyBrakeButtonLocked: boolean
  openLeftDoorFlag: boolean
  openRightDoorFlag: boolean
  closeLeftDoorFlag: boolean
  closeRightDoorFlag: boolean
  doorModeSwitchState?: string
  modeUpgradeConfirmFlag: boolean
  modeDowngradeConfirmFlag: boolean
  automaticTurnbackFlag: boolean
  atoStartFlag: boolean
  keySwitchLocked: boolean
  directionHandleState?: string
  masterHandleState?: string
  tractionNotchPercent: number
  brakeNotchPercent: number
}

export const vehicleApi = {
  maintenanceStates: () => request<VehicleMaintenanceState[]>('/vehicle/maintenance-states'),
  runtimeHealth: () => request<VehicleRuntimeStatusResponse>('/vehicle/runtime-health'),
  driverCabState: (trainId: string) =>
    request<Record<string, unknown>>(`/vehicle/driver-cabs/${encodeURIComponent(trainId)}/state`),
  /** 浏览器结构化输入 → 8080 编码 46 字节 PLC → 转发 9300 */
  driverCabPlcInput: (trainId: string, body: DriverCabPlcInputPacket) =>
    postJson<DriverCommandAcceptance>(`/vehicle/driver-cabs/${encodeURIComponent(trainId)}/plc-input`, body),
  driverCabPlcOutput: (trainId: string) =>
    requestBinary(`/vehicle/driver-cabs/${encodeURIComponent(trainId)}/plc-output`)
}
