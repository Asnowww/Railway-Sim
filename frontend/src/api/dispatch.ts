import { postJson, request } from '../shared/api/client'
import type {
  DispatchCommandView,
  DispatchDisturbance,
  DispatchRouteEstablishResponse,
  DispatchRouteInfo,
  DispatchSnapshot,
  OperationPlanRequest,
  OperationPlanView,
  OperationRouteCandidate,
  OperationRouteTemplate,
  RunPlanResponse,
  SignalDispatchPlanPublication,
  TrainStationEvent
} from '../types/dispatch'

export interface ManualCommandRequest {
  trainId: string
  commandType: string
  detail?: string
  targetHeadwaySec?: number
  speedBiasRatio?: number
  deltaDwellSec?: number
  routeId?: string
  payload?: Record<string, unknown>
}

export const dispatchApi = {
  plan: () => request<RunPlanResponse>('/dispatch/plan'),
  currentPlan: () => request<Record<string, unknown>>('/dispatch/plan/current'),
  status: () => request<DispatchSnapshot>('/dispatch/status'),
  disturbances: () => request<DispatchDisturbance[]>('/dispatch/disturbances'),
  /** 演示扰动注入（联调用） */
  injectDemoDisturbance: (body: {
    trainId?: string
    type?: string
    headwayDirection?: string
    targetHeadwaySec?: number
    actualHeadwaySec?: number
    violationSec?: number
    stationId?: string
  }) => postJson<DispatchDisturbance>('/dispatch/disturbances/demo', body),
  commands: () => request<DispatchCommandView[]>('/dispatch/commands'),
  submitCommand: (body: ManualCommandRequest) => postJson<DispatchCommandView>('/dispatch/commands', body),
  cancelCommand: (commandId: string) =>
    request<{ accepted: boolean; commandId: string }>(`/dispatch/commands/${encodeURIComponent(commandId)}/cancel`, {
      method: 'POST'
    }),
  routeList: () => request<DispatchRouteInfo[]>('/dispatch/route/list'),

  /* ---- 运营进路计划（operation-route / operation-plans，来自团队 lq 分支） ---- */
  operationRouteTemplates: () => request<OperationRouteTemplate[]>('/dispatch/operation-route/templates'),
  previewOperationRoute: (body: OperationPlanRequest) =>
    postJson<OperationRouteCandidate[]>('/dispatch/operation-route/preview', body),
  operationPlans: () => request<OperationPlanView[]>('/dispatch/operation-plans'),
  createOperationPlan: (body: OperationPlanRequest) => postJson<OperationPlanView>('/dispatch/operation-plans', body),
  cancelOperationPlan: (planId: string) =>
    request<OperationPlanView>(`/dispatch/operation-plans/${encodeURIComponent(planId)}/cancel`, { method: 'POST' }),
  publishSignalPlan: (body?: { operator?: string; effectiveFrom?: string }) =>
    postJson<SignalDispatchPlanPublication>('/dispatch/signal-publications', body ?? {}),
  adjustHeadway: (body: {
    trainId: string
    targetHeadwaySec: number
    regulationAction?: string
    frontTrainId?: string
  }) => postJson<DispatchCommandView>('/dispatch/headway/adjust', body),

  /** 联锁直连建路——仅供 /debug 联调，生产走 submitCommand({commandType:'REQUEST_ROUTE'}) 闭环 */
  establishRoute: (routeId: string, trainId: string) =>
    request<DispatchRouteEstablishResponse>(
      `/dispatch/route/establish?routeId=${encodeURIComponent(routeId)}&trainId=${encodeURIComponent(trainId)}`,
      { method: 'POST' }
    ),
  stationRecords: () => request<TrainStationEvent[]>('/dispatch/station-records')
}
