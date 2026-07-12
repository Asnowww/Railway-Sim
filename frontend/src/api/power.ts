import { apiBaseUrl } from './config'
import type { PowerSectionState } from '../types/simulation'

type ConfirmedMutationRequest = {
  faultType?: string
  reason?: string
  operator?: string
  traceId?: string
  confirmToken: 'SIMULATION_CONFIRM'
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(`${apiBaseUrl}${path}`, init)
  if (!response.ok) {
    throw new Error(`Request failed: ${response.status} ${response.statusText}`)
  }
  return response.json() as Promise<T>
}

export const powerApi = {
  sections: () => request<PowerSectionState[]>('/power/sections'),
  section: (sectionId: string) => request<PowerSectionState>(`/power/sections/${encodeURIComponent(sectionId)}`),
  events: (sectionId: string) => request<unknown[]>(`/power/sections/${encodeURIComponent(sectionId)}/events`),
  energy: () => request<unknown>('/power/energy'),
  maintenanceLocks: () => request<unknown[]>('/power/maintenance-locks'),
  substations: () => request<unknown[]>('/power/substations'),
  substationDevices: (substationId: string) => request<unknown[]>(`/power/substations/${encodeURIComponent(substationId)}/devices`),
  isolators: () => request<unknown[]>('/power/isolators'),
  strayCurrent: () => request<unknown[]>('/power/stray-current'),
  externalHealth: () => request<unknown>('/power/external-health'),
  externalEvents: () => request<unknown[]>('/power/external-events'),
  operate: (body: Record<string, unknown> & { confirmToken: 'SIMULATION_CONFIRM' }) =>
    request<unknown>('/power/operations', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body)
    }),
  injectFault: (sectionId: string, body: ConfirmedMutationRequest) =>
    request<unknown>(`/power/sections/${encodeURIComponent(sectionId)}/faults`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body)
    }),
  clearFault: (sectionId: string, body: ConfirmedMutationRequest) =>
    request<unknown>(`/power/sections/${encodeURIComponent(sectionId)}/faults/clear`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body)
    })
}
