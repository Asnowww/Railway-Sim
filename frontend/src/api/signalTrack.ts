import { apiBaseUrl } from './config'

type SignalTrackFaultRequest = {
  sourceId: string
  faultType: string
  operator?: string
  reason?: string
  traceId?: string
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(`${apiBaseUrl}${path}`, init)
  if (!response.ok) {
    throw new Error(`Request failed: ${response.status} ${response.statusText}`)
  }
  return response.json() as Promise<T>
}

export const signalTrackApi = {
  routes: () => request<unknown[]>('/signal-track/routes'),
  routeStatus: (routeId: string) => request<Record<string, unknown>>(`/signal-track/routes/${encodeURIComponent(routeId)}/status`),
  routeEvents: () => request<unknown[]>('/signal-track/route-events'),
  faults: () => request<string[]>('/signal-track/faults'),
  injectFault: (body: SignalTrackFaultRequest) =>
    request<unknown>('/signal-track/faults', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body)
    }),
  clearFault: (segmentId: string, operator = 'monitor-console', reason = '', traceId = '') =>
    request<unknown>(
      `/signal-track/faults/${encodeURIComponent(segmentId)}/clear?operator=${encodeURIComponent(operator)}&reason=${encodeURIComponent(reason)}&traceId=${encodeURIComponent(traceId)}`,
      { method: 'POST' }
    )
}
