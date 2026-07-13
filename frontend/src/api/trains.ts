import { apiBaseUrl } from './config'
import type { TrainState } from '../types/simulation'

type FaultMutationRequest = {
  faultType?: string
  reason?: string
  operator?: string
  traceId?: string
  confirmToken: 'SIMULATION_CONFIRM'
}

type TrainLifecycleCommandRequest = {
  action: string
  trains?: Array<{
    trainNo?: number
    linkId?: number
    offsetMeters?: number
    direction?: string
  }>
  reason?: string
  operator?: string
  confirmToken: 'SIMULATION_CONFIRM'
  traceId?: string
}

type VehicleRuntimeRegistrationRequest = {
  trainId?: string
  trainNo?: number
  linkId?: number
  offsetMeters?: number
  direction?: string
  reason?: string
  traceId?: string
  trainType?: string
  parameterSetId?: string
  lengthMeters?: number
  emptyMassKg?: number
  maxLoadMassKg?: number
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(`${apiBaseUrl}${path}`, init)
  if (!response.ok) {
    throw new Error(`Request failed: ${response.status} ${response.statusText}`)
  }
  return response.json() as Promise<T>
}

export const trainApi = {
  list: () => request<TrainState[]>('/trains'),
  detail: (trainId: string) => request<TrainState>(`/trains/${encodeURIComponent(trainId)}`),
  energy: (trainId: string) => request<unknown>(`/trains/${encodeURIComponent(trainId)}/energy`),
  applyLifecycle: (body: TrainLifecycleCommandRequest) =>
    request<TrainState[]>('/trains/lifecycle', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body)
    }),
  registerRuntimeStartedTrain: (body: VehicleRuntimeRegistrationRequest) =>
    request<TrainState>('/trains/runtime-registrations', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body)
    }),
  faults: (trainId: string) => request<unknown[]>(`/trains/${encodeURIComponent(trainId)}/faults`),
  injectFault: (trainId: string, body: FaultMutationRequest) =>
    request<unknown>(`/trains/${encodeURIComponent(trainId)}/faults`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body)
    }),
  clearFault: (trainId: string, body: FaultMutationRequest) =>
    request<unknown>(`/trains/${encodeURIComponent(trainId)}/faults/clear`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body)
    })
}
