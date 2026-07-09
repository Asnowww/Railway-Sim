import type { DispatchCommandView, DispatchSnapshot, RunPlanResponse, TrainStationEvent } from '../types/dispatch'
import type { DispatchDisturbance } from '../types/dispatch'

async function request<T>(url: string, init?: RequestInit): Promise<T> {
  const response = await fetch(url, init)
  if (!response.ok) {
    throw new Error(`Request failed: ${response.status} ${response.statusText}`)
  }
  return response.json() as Promise<T>
}

export const dispatchApi = {
  plan: () => request<RunPlanResponse>('/api/dispatch/plan'),
  currentPlan: () => request<Record<string, unknown>>('/api/dispatch/plan/current'),
  status: () => request<DispatchSnapshot>('/api/dispatch/status'),
  disturbances: () => request<DispatchDisturbance[]>('/api/dispatch/disturbances'),
  commands: () => request<DispatchCommandView[]>('/api/dispatch/commands'),
  submitCommand: (body: {
    trainId: string
    commandType: string
    detail?: string
    targetHeadwaySec?: number
    speedBiasRatio?: number
    deltaDwellSec?: number
  }) =>
    request<DispatchCommandView>('/api/dispatch/commands', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body)
    }),
  cancelCommand: (commandId: string) =>
    request<Record<string, unknown>>(`/api/dispatch/commands/${encodeURIComponent(commandId)}/cancel`, {
      method: 'POST'
    }),
  stationRecords: () => request<TrainStationEvent[]>('/api/dispatch/station-records')
}
