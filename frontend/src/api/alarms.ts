import { postJson, request } from '../shared/api/client'
import type { AlarmRecord } from '../types/ops'

export const alarmApi = {
  list: (runId?: string) => request<AlarmRecord[]>(runId ? `/alarms?runId=${encodeURIComponent(runId)}` : '/alarms'),
  /** 404=告警不存在，409=状态冲突（如已清除），由 ApiError 携带语义 */
  acknowledge: (alarmId: string, operator: string) =>
    postJson<AlarmRecord>(`/alarms/${encodeURIComponent(alarmId)}/acknowledge`, { operator })
}
