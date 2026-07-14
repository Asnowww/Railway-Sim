import { postJson, request, SIMULATION_CONFIRM_TOKEN } from '../shared/api/client'
import type { TrainState } from '../types/simulation'
import type { TrainFaultRecord } from '../types/vehicles'
import type { TrainEnergyResponse } from '../types/power'

export interface FaultMutationRequest {
  faultType?: string
  reason?: string
  operator?: string
  traceId?: string
}

export interface TrainLifecycleCommandRequest {
  action: 'ADD' | 'DELETE' | 'CLEAR' | string
  trains?: Array<{
    trainNo?: number
    linkId?: number
    offsetMeters?: number
    direction?: string
  }>
  reason?: string
  operator?: string
  traceId?: string
}

export const trainApi = {
  list: () => request<TrainState[]>('/trains'),
  detail: (trainId: string) => request<TrainState>(`/trains/${encodeURIComponent(trainId)}`),
  energy: (trainId: string) => request<TrainEnergyResponse>(`/trains/${encodeURIComponent(trainId)}/energy`),
  faults: (trainId: string) => request<TrainFaultRecord[]>(`/trains/${encodeURIComponent(trainId)}/faults`),
  /** 危险写：必须先经 requestConfirm 二次确认 */
  injectFault: (trainId: string, body: FaultMutationRequest) =>
    postJson<unknown>(`/trains/${encodeURIComponent(trainId)}/faults`, { ...body, confirmToken: SIMULATION_CONFIRM_TOKEN }),
  clearFault: (trainId: string, body: FaultMutationRequest) =>
    postJson<unknown>(`/trains/${encodeURIComponent(trainId)}/faults/clear`, {
      ...body,
      confirmToken: SIMULATION_CONFIRM_TOKEN
    }),
  /** 联调入口，仅 /debug 使用 */
  applyLifecycle: (body: TrainLifecycleCommandRequest) =>
    postJson<TrainState[]>('/trains/lifecycle', { ...body, confirmToken: SIMULATION_CONFIRM_TOKEN })
}
