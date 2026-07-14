import { postJson, request } from '../shared/api/client'
import type { ServiceHealthRecord } from '../types/ops'

export const serviceHealthApi = {
  list: () => request<ServiceHealthRecord[]>('/service-health'),
  detail: (serviceId: string) => request<ServiceHealthRecord>(`/service-health/${encodeURIComponent(serviceId)}`),
  recoveryCheck: (serviceId: string, body: { expectedRunId: string; expectedTick: number }) =>
    postJson<ServiceHealthRecord>(`/service-health/${encodeURIComponent(serviceId)}/recovery/check`, body)
}
