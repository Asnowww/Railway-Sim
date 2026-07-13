import { apiBaseUrl } from './config'

type VisionVehicleStateRequest = {
  speedMetersPerSecond?: number
  accelerationMetersPerSecondSquared?: number
  accelerationPercent?: number
  headPositionMeters?: number
  headSegmentId?: string
  directionCode?: number
  runCondition?: string
  headlightState?: string
  operationCode?: number
  departureCountdownSeconds?: number
}

type TrainOperationalTelemetry = {
  trainNo: number
  speedMetersPerSecond: number
  cumulativeDistanceMeters: number
  direction?: string
  loadMassKg: number
  faultSpeedLimitMetersPerSecond: number
  emergencyBrakeApplied: boolean
  availableTractionCount: number
  availableBrakeCount: number
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(`${apiBaseUrl}${path}`, init)
  if (!response.ok) {
    throw new Error(`Request failed: ${response.status} ${response.statusText}`)
  }
  return response.json() as Promise<T>
}

export const signalVehicleApi = {
  statuses: () => request<unknown[]>('/signal/vehicles/statuses'),
  commands: () => request<unknown[]>('/signal/vehicles/commands'),
  applyTelemetry: (telemetries: TrainOperationalTelemetry[]) =>
    request<unknown[]>('/signal/vehicles/telemetry', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(telemetries)
    }),
  applyTelemetryContentPacket: (trainCount: number, payload: ArrayBuffer | Uint8Array) => {
    const body: ArrayBuffer = payload instanceof Uint8Array
      ? new Uint8Array(payload).buffer
      : payload
    return request<unknown[]>(`/signal/vehicles/telemetry/content-packet?trainCount=${encodeURIComponent(String(trainCount))}`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/octet-stream' },
      body
    })
  },
  putVisionState: (trainId: string, body: VisionVehicleStateRequest) =>
    request<unknown>(`/signal/vehicles/${encodeURIComponent(trainId)}/vision-state`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body)
    }),
  visionStates: () => request<unknown[]>('/signal/vehicles/vision-states'),
  sendVisionUdp: (trainId: string, host: string, port: number) =>
    request<unknown>(
      `/signal/vision/udp/send?trainId=${encodeURIComponent(trainId)}&host=${encodeURIComponent(host)}&port=${encodeURIComponent(String(port))}`,
      { method: 'POST' }
    )
}
