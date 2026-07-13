import { postJson, request } from '../shared/api/client'

export interface SignalTrackFaultRequest {
  sourceId: string
  faultType: string
  operator?: string
  reason?: string
  traceId?: string
}

export interface FaultMutationResponse {
  accepted: boolean
  changed: boolean
  idempotent: boolean
  operation: string
  sourceId: string
  faultType?: string
  operator?: string
  reason?: string
  traceId?: string
}

export interface RouteLifecycleEvent {
  routeId?: string
  trainId?: string | null
  from?: string
  to?: string
  tick?: number
  interlockingState?: string
  detail?: string
  [key: string]: unknown
}

export interface SignalRouteInfo {
  routeId: string
  name?: string
  type?: string
  fromStation?: string
  toStation?: string
  segmentIds?: string[]
  status?: string
  [key: string]: unknown
}

export const signalTrackApi = {
  routes: () => request<SignalRouteInfo[]>('/signal-track/routes'),
  routeStatus: (routeId: string) =>
    request<{ routeId: string; status: string }>(`/signal-track/routes/${encodeURIComponent(routeId)}/status`),
  routeEvents: () => request<RouteLifecycleEvent[]>('/signal-track/route-events'),
  faults: () => request<string[]>('/signal-track/faults'),
  injectFault: (body: SignalTrackFaultRequest) => postJson<FaultMutationResponse>('/signal-track/faults', body),
  clearFault: (segmentId: string, operator = 'monitor-console', reason = '', traceId = '') =>
    request<FaultMutationResponse>(
      `/signal-track/faults/${encodeURIComponent(segmentId)}/clear?operator=${encodeURIComponent(operator)}&reason=${encodeURIComponent(reason)}&traceId=${encodeURIComponent(traceId)}`,
      { method: 'POST' }
    )
}
