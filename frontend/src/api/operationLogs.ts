import { request } from '../shared/api/client'
import type { AuditHealthResponse, OperationLogEntry } from '../types/ops'

export const operationLogApi = {
  list: (targetRef?: string) =>
    request<OperationLogEntry[]>(targetRef ? `/operation-logs?targetRef=${encodeURIComponent(targetRef)}` : '/operation-logs'),
  health: () => request<AuditHealthResponse>('/operation-logs/health')
}
