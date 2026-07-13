import { apiBaseUrl } from './config'
import type {
  DispatchCommandView,
  OperationPlanRequest,
  OperationPlanView,
  OperationRouteCandidate,
  OperationRouteTemplate,
  DispatchRouteEstablishResponse,
  DispatchRouteInfo,
  DispatchSnapshot,
  RunPlanResponse,
  TrainStationEvent
} from '../types/dispatch'
import type { DispatchDisturbance } from '../types/dispatch'

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(`${apiBaseUrl}${path}`, init)
  if (!response.ok) {
    throw new Error(`${path} 请求失败：${response.status} ${response.statusText}`)
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
    routeId?: string
    payload?: Record<string, unknown>
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
  routeList: () => request<DispatchRouteInfo[]>('/dispatch/route/list'),
  operationRouteTemplates: () => request<OperationRouteTemplate[]>('/dispatch/operation-route/templates'),
  previewOperationRoute: (body: OperationPlanRequest) =>
    request<OperationRouteCandidate[]>('/dispatch/operation-route/preview', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body)
    }),
  operationPlans: () => request<OperationPlanView[]>('/dispatch/operation-plans'),
  createOperationPlan: (body: OperationPlanRequest) =>
    request<OperationPlanView>('/dispatch/operation-plans', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body)
    }),
  cancelOperationPlan: (planId: string) =>
    request<OperationPlanView>(`/dispatch/operation-plans/${encodeURIComponent(planId)}/cancel`, {
      method: 'POST'
    }),
  adjustHeadway: (body: {
    trainId: string
    targetHeadwaySec: number
    regulationAction?: string
    frontTrainId?: string
  }) =>
    request<DispatchCommandView>('/dispatch/headway/adjust', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body)
    }),
  establishRoute: (routeId: string, trainId: string) =>
    request<DispatchRouteEstablishResponse>(
      `/dispatch/route/establish?routeId=${encodeURIComponent(routeId)}&trainId=${encodeURIComponent(trainId)}`,
      { method: 'POST' }
    ),
  stationRecords: () => request<TrainStationEvent[]>('/dispatch/station-records')
}
