/** 告警、审计、运行历史、服务健康、局域网适配器等运维域类型。 */

export interface FaultImpact {
  severity?: string
  affectedTrainIds?: string[]
  affectedSectionIds?: string[]
  safetyAction?: string
  clearCondition?: string
  recoveryCondition?: string
  [key: string]: unknown
}

/** GET /api/alarms 完整生命周期记录（快照内的 Alarm 是精简版）。 */
export interface AlarmRecord {
  id: string
  simulationRunId?: string
  alarmCode?: string
  sourceModule: string
  locationRef: string
  level: number
  title: string
  detail: string
  state?: 'RAISED' | 'ACKNOWLEDGED' | 'CLEARED' | string
  raisedAt: string
  lastSeenAt?: string
  acknowledgedAt?: string | null
  acknowledgedBy?: string | null
  clearedAt?: string | null
  confirmed?: boolean
  impact?: FaultImpact | null
}

export interface OperationLogEntry {
  id?: string | number
  operationType?: string
  targetRef?: string
  operator?: string
  reason?: string
  traceId?: string
  beforeState?: string
  afterState?: string
  status?: string
  retryCount?: number
  createdAt?: string
  [key: string]: unknown
}

export interface AuditHealthResponse {
  status: 'UP' | 'PENDING' | 'DEGRADED' | string
  totalPersisted?: number
  failed?: number
  pending?: number
  [key: string]: unknown
}

export interface ServiceHealthRecord {
  serviceId: string
  /** 后端字段名为 state（UP/DEGRADED/STALE/FALLBACK/RECOVERING） */
  state: string
  dataQuality?: string
  sourceTimestamp?: string | null
  observedAt?: string | null
  simulationRunId?: string | null
  lastAcceptedTick?: number
  modelVersion?: string
  parameterVersion?: string
  reason?: string | null
  recoveryGate?: { rejectionReasons?: string[]; [key: string]: unknown } | null
  [key: string]: unknown
}

export interface SimulationRunRecord {
  runId?: string
  id?: string
  status?: string
  startedAt?: string
  endedAt?: string | null
  createdAt?: string
  lastTick?: number
  endReason?: string | null
  [key: string]: unknown
}

export interface TrainStopStatistics {
  sampleCount?: number
  meanAbsoluteErrorMeters?: number
  p95AbsoluteErrorMeters?: number
  successCount?: number
  successRate?: number
  overrunCount?: number
  emergencyBrakeCount?: number
  sampleRequirementMet?: boolean
  [key: string]: unknown
}

export interface LocalNetHealth {
  adapterId: string
  protocolFamily?: string
  configured?: boolean
  enabled?: boolean
  running?: boolean
  receivedCount?: number
  sentCount?: number
  errorCount?: number
  lastError?: string | null
  lastPacketSummary?: string | null
  [key: string]: unknown
}
