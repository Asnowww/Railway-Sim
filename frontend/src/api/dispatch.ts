import { apiBaseUrl } from './config'
import type { DispatchCommandView, DispatchSnapshot, RunPlanResponse, TrainStationEvent } from '../types/dispatch'
import type { DispatchDisturbance } from '../types/dispatch'

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(`${apiBaseUrl}${path}`, init)
  if (!response.ok) {
    throw new Error(`Request failed: ${response.status} ${response.statusText}`)
  }
  return response.json() as Promise<T>
}

export const dispatchApi = {
  plan: () => request<RunPlanResponse>('/dispatch/plan'),
  currentPlan: () => request<Record<string, unknown>>('/dispatch/plan/current'),
  status: () => request<DispatchSnapshot>('/dispatch/status'),
  disturbances: () => request<DispatchDisturbance[]>('/dispatch/disturbances'),
  commands: () => request<DispatchCommandView[]>('/dispatch/commands'),
  submitCommand: (body: {
    trainId: string
    commandType: string
    detail?: string
    targetHeadwaySec?: number
    speedBiasRatio?: number
    deltaDwellSec?: number
  }) =>
    request<DispatchCommandView>('/dispatch/commands', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body)
    }),
  cancelCommand: (commandId: string) =>
    request<Record<string, unknown>>(`/dispatch/commands/${encodeURIComponent(commandId)}/cancel`, {
      method: 'POST'
    }),
  stationRecords: () => request<TrainStationEvent[]>('/dispatch/station-records')
}
