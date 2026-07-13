import { postJson, request, SIMULATION_CONFIRM_TOKEN } from '../shared/api/client'
import type { PowerSectionState } from '../types/simulation'
import type {
  IsolatorState,
  PowerEnergyResponse,
  PowerExternalHealth,
  PowerMaintenanceLock,
  StrayCurrentRisk,
  SubstationDevice,
  SubstationState
} from '../types/power'

export interface PowerFaultMutationRequest {
  faultType?: string
  reason?: string
  operator?: string
  traceId?: string
}

export interface PowerOperationRequest {
  targetType: string
  targetId: string
  desiredState: string
  operator?: string
  reason?: string
  traceId?: string
}

export const powerApi = {
  sections: () => request<PowerSectionState[]>('/power/sections'),
  section: (sectionId: string) => request<PowerSectionState>(`/power/sections/${encodeURIComponent(sectionId)}`),
  events: (sectionId: string) => request<unknown[]>(`/power/sections/${encodeURIComponent(sectionId)}/events`),
  energy: () => request<PowerEnergyResponse>('/power/energy'),
  maintenanceLocks: () => request<PowerMaintenanceLock[]>('/power/maintenance-locks'),
  substations: () => request<SubstationState[]>('/power/substations'),
  substationDevices: (substationId: string) =>
    request<SubstationDevice[]>(`/power/substations/${encodeURIComponent(substationId)}/devices`),
  isolators: () => request<IsolatorState[]>('/power/isolators'),
  strayCurrent: () => request<StrayCurrentRisk[]>('/power/stray-current'),
  externalHealth: () => request<PowerExternalHealth>('/power/external-health'),
  externalEvents: () => request<unknown[]>('/power/external-events'),
  /** 危险写：设备级操作（隔离开关/断路器/排流柜），必须先经二次确认 */
  operate: (body: PowerOperationRequest) =>
    postJson<unknown>('/power/operations', { ...body, confirmToken: SIMULATION_CONFIRM_TOKEN }),
  injectFault: (sectionId: string, body: PowerFaultMutationRequest) =>
    postJson<unknown>(`/power/sections/${encodeURIComponent(sectionId)}/faults`, {
      ...body,
      confirmToken: SIMULATION_CONFIRM_TOKEN
    }),
  clearFault: (sectionId: string, body: PowerFaultMutationRequest) =>
    postJson<unknown>(`/power/sections/${encodeURIComponent(sectionId)}/faults/clear`, {
      ...body,
      confirmToken: SIMULATION_CONFIRM_TOKEN
    })
}
