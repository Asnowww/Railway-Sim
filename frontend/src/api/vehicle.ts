import { apiBaseUrl } from './config'

async function request<T>(path: string): Promise<T> {
  const response = await fetch(`${apiBaseUrl}${path}`)
  if (!response.ok) {
    throw new Error(`Request failed: ${response.status} ${response.statusText}`)
  }
  return response.json() as Promise<T>
}

export const vehicleApi = {
  maintenanceStates: () => request<unknown[]>('/vehicle/maintenance-states'),
  runtimeHealth: () => request<unknown>('/vehicle/runtime-health'),
  driverCabState: (trainId: string) => request<unknown>(`/vehicle/driver-cabs/${encodeURIComponent(trainId)}/state`),
  driverCabPlcOutput: (trainId: string) => request<unknown>(`/vehicle/driver-cabs/${encodeURIComponent(trainId)}/plc-output`)
}
