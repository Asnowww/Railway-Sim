import { apiBaseUrl } from './config'

type DriverCabPlcInputPacket = {
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

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(`${apiBaseUrl}${path}`, init)
  if (!response.ok) {
    throw new Error(`Request failed: ${response.status} ${response.statusText}`)
  }
  return response.json() as Promise<T>
}

async function requestArrayBuffer(path: string): Promise<ArrayBuffer> {
  const response = await fetch(`${apiBaseUrl}${path}`)
  if (!response.ok) {
    throw new Error(`Request failed: ${response.status} ${response.statusText}`)
  }
  return response.arrayBuffer()
}

export const vehicleApi = {
  maintenanceStates: () => request<unknown[]>('/vehicle/maintenance-states'),
  runtimeHealth: () => request<unknown>('/vehicle/runtime-health'),
  driverCabState: (trainId: string) => request<unknown>(`/vehicle/driver-cabs/${encodeURIComponent(trainId)}/state`),
  driverCabPlcInput: (trainId: string, body: DriverCabPlcInputPacket) =>
    request<unknown>(`/vehicle/driver-cabs/${encodeURIComponent(trainId)}/plc-input`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body)
    }),
  driverCabPlcOutput: (trainId: string) => requestArrayBuffer(`/vehicle/driver-cabs/${encodeURIComponent(trainId)}/plc-output`)
}
