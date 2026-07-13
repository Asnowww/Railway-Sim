<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { storeToRefs } from 'pinia'
import { useSimulationStore } from '../../stores/simulation'
import { useConnectionStore } from '../../stores/connection'
import { alarmApi } from '../../api/alarms'
import { operationLogApi } from '../../api/operationLogs'
import { simulationRunsApi } from '../../api/simulationRuns'
import { localNetApi } from '../../api/localNet'
import Panel from '../../shared/components/Panel.vue'
import StatusBadge from '../../shared/components/StatusBadge.vue'
import { toastError, toastOk } from '../../shared/toast'
import {
  alarmLevelLabel,
  alarmLevelTone,
  alarmStateLabel,
  dataQualityLabel,
  healthTone,
  serviceHealthLabel
} from '../../shared/labels'
import type { AlarmRecord, AuditHealthResponse, LocalNetHealth, OperationLogEntry, SimulationRunRecord } from '../../types/ops'

type TabKey = 'alarms' | 'runs' | 'audit' | 'health'

const activeTab = ref<TabKey>('alarms')
const tabs: Array<{ key: TabKey; label: string }> = [
  { key: 'alarms', label: '告警中心' },
  { key: 'runs', label: '运行历史' },
  { key: 'audit', label: '操作日志' },
  { key: 'health', label: '服务健康' }
]

const simulation = useSimulationStore()
const connection = useConnectionStore()
const { simulationRunId } = storeToRefs(simulation)

/* ---------- Tab1 告警中心 ---------- */

const alarmRecords = ref<AlarmRecord[]>([])
const alarmFilter = ref<'ALL' | 'UNACKED' | 'CRITICAL'>('ALL')
const alarmError = ref('')
const acknowledging = ref<string | null>(null)

async function loadAlarms(): Promise<void> {
  try {
    alarmRecords.value = await alarmApi.list(simulationRunId.value ?? undefined)
    alarmError.value = ''
  } catch (error) {
    alarmError.value = error instanceof Error ? error.message : String(error)
  }
}

const filteredAlarms = computed(() => {
  const list = [...alarmRecords.value].sort((a, b) => (b.raisedAt ?? '').localeCompare(a.raisedAt ?? ''))
  if (alarmFilter.value === 'UNACKED') return list.filter((alarm) => (alarm.state ?? (alarm.confirmed ? 'ACKNOWLEDGED' : 'RAISED')) === 'RAISED')
  if (alarmFilter.value === 'CRITICAL') return list.filter((alarm) => alarm.level >= 3)
  return list
})

async function acknowledgeAlarm(alarm: AlarmRecord): Promise<void> {
  acknowledging.value = alarm.id
  try {
    await alarmApi.acknowledge(alarm.id, 'dispatcher-01')
    toastOk('告警已确认', alarm.title)
    await loadAlarms()
  } catch (error) {
    toastError('确认失败', error)
  } finally {
    acknowledging.value = null
  }
}

/* ---------- Tab2 运行历史 ---------- */

const runs = ref<SimulationRunRecord[]>([])
const selectedRunId = ref('')
const runSummary = ref<Record<string, unknown> | null>(null)
const runDetailTab = ref<'summary' | 'stop-results' | 'faults' | 'control-decisions'>('summary')
const runDetailRows = ref<Record<string, unknown>[]>([])
const runsError = ref('')

async function loadRuns(): Promise<void> {
  try {
    runs.value = await simulationRunsApi.list(50)
    runsError.value = ''
  } catch (error) {
    runsError.value = error instanceof Error ? error.message : String(error)
  }
}

function runIdOf(record: SimulationRunRecord): string {
  return String(record.runId ?? record.id ?? '')
}

async function loadRunDetail(): Promise<void> {
  if (!selectedRunId.value) return
  runDetailRows.value = []
  runSummary.value = null
  try {
    if (runDetailTab.value === 'summary') {
      runSummary.value = await simulationRunsApi.summary(selectedRunId.value)
    } else if (runDetailTab.value === 'stop-results') {
      runDetailRows.value = await simulationRunsApi.stopResults(selectedRunId.value, 50)
    } else if (runDetailTab.value === 'faults') {
      runDetailRows.value = await simulationRunsApi.faults(selectedRunId.value, 50)
    } else {
      runDetailRows.value = await simulationRunsApi.controlDecisions(selectedRunId.value, 50)
    }
  } catch (error) {
    toastError('运行明细加载失败', error)
  }
}

watch([selectedRunId, runDetailTab], () => void loadRunDetail())

const runDetailColumns = computed(() => {
  const first = runDetailRows.value[0]
  if (!first) return []
  return Object.keys(first).slice(0, 8)
})

/* ---------- Tab3 操作日志 ---------- */

const operationLogs = ref<OperationLogEntry[]>([])
const auditHealth = ref<AuditHealthResponse | null>(null)
const targetRefFilter = ref('')
const auditError = ref('')

async function loadAudit(): Promise<void> {
  const [logsResult, healthResult] = await Promise.allSettled([
    operationLogApi.list(targetRefFilter.value || undefined),
    operationLogApi.health()
  ])
  if (logsResult.status === 'fulfilled') {
    operationLogs.value = logsResult.value
    auditError.value = ''
  } else {
    auditError.value = String(logsResult.reason instanceof Error ? logsResult.reason.message : logsResult.reason)
  }
  if (healthResult.status === 'fulfilled') auditHealth.value = healthResult.value
}

/* ---------- Tab4 服务健康 + localnet ---------- */

const localNetAdapters = ref<LocalNetHealth[]>([])

async function loadHealthTab(): Promise<void> {
  await connection.refreshServiceHealth()
  try {
    localNetAdapters.value = await localNetApi.adapters()
  } catch {
    localNetAdapters.value = []
  }
}

const recoveringBusy = ref<string | null>(null)

async function recoveryCheck(serviceId: string): Promise<void> {
  recoveringBusy.value = serviceId
  try {
    const { serviceHealthApi } = await import('../../api/serviceHealth')
    const result = await serviceHealthApi.recoveryCheck(serviceId, {
      expectedRunId: simulationRunId.value ?? '',
      expectedTick: simulation.tick
    })
    toastOk('恢复检查完成', `${serviceId} → ${serviceHealthLabel(result.state)}`)
    await connection.refreshServiceHealth()
  } catch (error) {
    toastError('恢复检查失败', error)
  } finally {
    recoveringBusy.value = null
  }
}

onMounted(() => {
  void loadAlarms()
  void loadRuns()
  void loadAudit()
  void loadHealthTab()
})

function formatTime(iso?: string | null): string {
  if (!iso) return '—'
  const date = new Date(iso)
  return Number.isNaN(date.getTime()) ? String(iso) : date.toLocaleString('zh-CN', { hour12: false })
}
</script>

<template>
  <div class="ops-view">
    <div class="tab-bar" role="tablist">
      <button
        v-for="tab in tabs"
        :key="tab.key"
        type="button"
        role="tab"
        :aria-selected="activeTab === tab.key"
        :class="['tab', { active: activeTab === tab.key }]"
        @click="activeTab = tab.key"
      >
        {{ tab.label }}
      </button>
    </div>

    <!-- 告警中心 -->
    <template v-if="activeTab === 'alarms'">
      <Panel title="告警中心" :subtitle="`当前运行 ${simulationRunId ?? '—'} · ${alarmRecords.length} 条`" flush>
        <template #actions>
          <select v-model="alarmFilter" aria-label="告警过滤">
            <option value="ALL">全部</option>
            <option value="UNACKED">未确认</option>
            <option value="CRITICAL">仅严重</option>
          </select>
          <button type="button" class="btn sm" @click="loadAlarms">刷新</button>
        </template>
        <p v-if="alarmError" class="error-line">告警列表加载失败：{{ alarmError }}（若为跨域拦截，请通过 Vite 代理访问或为后端补充 CORS）</p>
        <div class="scroll-y alarm-table">
          <table class="data">
            <thead>
              <tr>
                <th>等级</th><th>时间</th><th>来源</th><th>位置</th><th>标题</th><th>状态</th><th>影响</th><th></th>
              </tr>
            </thead>
            <tbody>
              <tr v-if="filteredAlarms.length === 0"><td colspan="8" class="empty">无告警</td></tr>
              <tr v-for="alarm in filteredAlarms" :key="alarm.id">
                <td>
                  <StatusBadge
                    :tone="alarmLevelTone(alarm.level)"
                    :label="alarmLevelLabel(alarm.level)"
                    :blink="alarm.level >= 3 && (alarm.state ?? 'RAISED') === 'RAISED'"
                  />
                </td>
                <td class="num small">{{ formatTime(alarm.raisedAt) }}</td>
                <td>{{ alarm.sourceModule }}</td>
                <td class="mono">{{ alarm.locationRef }}</td>
                <td>
                  <strong>{{ alarm.title }}</strong>
                  <p class="detail-text">{{ alarm.detail }}</p>
                </td>
                <td>
                  <StatusBadge
                    :tone="(alarm.state ?? 'RAISED') === 'RAISED' ? 'danger' : (alarm.state === 'CLEARED' ? 'neutral' : 'ok')"
                    :label="alarmStateLabel(alarm.state ?? (alarm.confirmed ? 'ACKNOWLEDGED' : 'RAISED'))"
                  />
                  <p v-if="alarm.acknowledgedBy" class="detail-text">{{ alarm.acknowledgedBy }} @ {{ formatTime(alarm.acknowledgedAt) }}</p>
                </td>
                <td class="small">
                  <template v-if="alarm.impact">
                    <span v-if="alarm.impact.severity">严重度 {{ alarm.impact.severity }}；</span>
                    <span v-if="alarm.impact.affectedTrainIds?.length">列车 {{ alarm.impact.affectedTrainIds.join(',') }}；</span>
                    <span v-if="alarm.impact.affectedSectionIds?.length">区段 {{ alarm.impact.affectedSectionIds.join(',') }}；</span>
                    <span v-if="alarm.impact.safetyAction">动作 {{ alarm.impact.safetyAction }}</span>
                  </template>
                  <span v-else>—</span>
                </td>
                <td>
                  <button
                    type="button"
                    class="btn sm"
                    :disabled="(alarm.state ?? 'RAISED') !== 'RAISED' || acknowledging === alarm.id"
                    @click="acknowledgeAlarm(alarm)"
                  >
                    确认
                  </button>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </Panel>
    </template>

    <!-- 运行历史 -->
    <template v-else-if="activeTab === 'runs'">
      <div class="runs-grid">
        <Panel title="仿真运行历史" flush>
          <template #actions>
            <button type="button" class="btn sm" @click="loadRuns">刷新</button>
          </template>
          <p v-if="runsError" class="error-line">{{ runsError }}</p>
          <div class="scroll-y runs-table">
            <table class="data">
              <thead><tr><th>运行 ID</th><th>状态</th><th>开始</th><th>结束</th><th>末 Tick</th></tr></thead>
              <tbody>
                <tr v-if="runs.length === 0"><td colspan="5" class="empty">无历史运行</td></tr>
                <tr
                  v-for="record in runs"
                  :key="runIdOf(record)"
                  :class="['clickable', { selected: runIdOf(record) === selectedRunId }]"
                  @click="selectedRunId = runIdOf(record)"
                >
                  <td class="mono small">{{ runIdOf(record) }}</td>
                  <td>{{ record.status ?? '—' }}<span v-if="record.endReason" class="small muted">（{{ record.endReason }}）</span></td>
                  <td class="num small">{{ formatTime(record.startedAt ?? record.createdAt) }}</td>
                  <td class="num small">{{ formatTime(record.endedAt) }}</td>
                  <td class="num small">{{ record.lastTick ?? '—' }}</td>
                </tr>
              </tbody>
            </table>
          </div>
        </Panel>

        <Panel :title="selectedRunId ? `运行详情 · ${selectedRunId}` : '运行详情'" flush>
          <template #actions>
            <select v-model="runDetailTab" :disabled="!selectedRunId">
              <option value="summary">汇总</option>
              <option value="stop-results">停车结果</option>
              <option value="faults">故障记录</option>
              <option value="control-decisions">控制决策</option>
            </select>
          </template>
          <p v-if="!selectedRunId" class="empty">从左侧选择一次运行</p>
          <div v-else-if="runDetailTab === 'summary'" class="summary-block scroll-y">
            <pre v-if="runSummary" class="mono">{{ JSON.stringify(runSummary, null, 2) }}</pre>
            <p v-else class="empty">加载中…</p>
          </div>
          <div v-else class="scroll-y runs-table">
            <table class="data">
              <thead>
                <tr><th v-for="column in runDetailColumns" :key="column">{{ column }}</th></tr>
              </thead>
              <tbody>
                <tr v-if="runDetailRows.length === 0"><td :colspan="Math.max(1, runDetailColumns.length)" class="empty">无记录</td></tr>
                <tr v-for="(row, index) in runDetailRows" :key="index">
                  <td v-for="column in runDetailColumns" :key="column" class="small mono">{{ String(row[column] ?? '—') }}</td>
                </tr>
              </tbody>
            </table>
          </div>
        </Panel>
      </div>
    </template>

    <!-- 操作日志 -->
    <template v-else-if="activeTab === 'audit'">
      <Panel title="操作审计日志" flush>
        <template #actions>
          <StatusBadge
            v-if="auditHealth"
            :tone="auditHealth.status === 'UP' ? 'ok' : auditHealth.status === 'DEGRADED' ? 'danger' : 'warn'"
            :label="`审计链路 ${auditHealth.status}（已入库 ${auditHealth.totalPersisted ?? 0} / 失败 ${auditHealth.failed ?? 0}）`"
          />
          <input v-model="targetRefFilter" type="text" placeholder="按目标过滤，如 T02 / TR-001" aria-label="目标过滤" @keyup.enter="loadAudit" />
          <button type="button" class="btn sm" @click="loadAudit">查询</button>
        </template>
        <p v-if="auditError" class="error-line">{{ auditError }}</p>
        <div class="scroll-y audit-table">
          <table class="data">
            <thead>
              <tr><th>时间</th><th>操作</th><th>目标</th><th>状态变化</th><th>操作员</th><th>原因</th><th>结果</th><th>traceId</th></tr>
            </thead>
            <tbody>
              <tr v-if="operationLogs.length === 0"><td colspan="8" class="empty">无操作日志</td></tr>
              <tr v-for="(log, index) in operationLogs" :key="index">
                <td class="num small">{{ formatTime(log.createdAt) }}</td>
                <td>{{ log.operationType ?? '—' }}</td>
                <td class="mono">{{ log.targetRef ?? '—' }}</td>
                <td class="mono small">{{ log.beforeState ?? '—' }} → {{ log.afterState ?? '—' }}</td>
                <td>{{ log.operator ?? '—' }}</td>
                <td class="small">{{ log.reason ?? '—' }}</td>
                <td class="small">{{ log.status ?? '—' }}</td>
                <td class="mono small">{{ log.traceId ?? '—' }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </Panel>
    </template>

    <!-- 服务健康 -->
    <template v-else>
      <div class="health-grid">
        <Panel title="外部服务健康门禁" subtitle="异常恢复必须通过 runId / tick / 拓扑 hash / 参数版本门槛">
          <template #actions>
            <button type="button" class="btn sm" @click="loadHealthTab">刷新</button>
          </template>
          <p v-if="connection.serviceHealthError" class="error-line">{{ connection.serviceHealthError }}</p>
          <div class="health-cards">
            <p v-if="connection.serviceHealth.length === 0" class="empty">暂无服务健康记录</p>
            <article v-for="service in connection.serviceHealth" :key="service.serviceId" class="health-card">
              <header>
                <strong class="mono">{{ service.serviceId }}</strong>
                <StatusBadge :tone="healthTone(service.state)" :label="serviceHealthLabel(service.state)" />
              </header>
              <p class="small muted">
                观测 {{ formatTime(service.observedAt) }} · 源时间 {{ formatTime(service.sourceTimestamp) }}
                <template v-if="service.lastAcceptedTick !== undefined">· 已接受 Tick {{ service.lastAcceptedTick }}</template>
              </p>
              <p class="small muted">
                run <span class="mono">{{ service.simulationRunId ?? '—' }}</span>
                <template v-if="service.modelVersion">· 模型 {{ service.modelVersion }}</template>
              </p>
              <p v-if="service.reason" class="small">{{ service.reason }}</p>
              <ul v-if="service.recoveryGate?.rejectionReasons?.length" class="reject-list">
                <li v-for="(reason, index) in service.recoveryGate.rejectionReasons" :key="index">{{ reason }}</li>
              </ul>
              <button
                v-if="service.state === 'RECOVERING' || service.state === 'FALLBACK' || service.state === 'STALE'"
                type="button"
                class="btn sm"
                :disabled="recoveringBusy === service.serviceId"
                @click="recoveryCheck(service.serviceId)"
              >
                触发恢复检查
              </button>
            </article>
          </div>
        </Panel>

        <Panel title="局域网适配器" subtitle="机房现场链路（信号 UDP / 司机台 TCP / 供电点表）" flush>
          <div class="scroll-y localnet-table">
            <table class="data">
              <thead>
                <tr><th>适配器</th><th>协议</th><th>配置/启用/运行</th><th>收/发/错</th><th>最近错误</th></tr>
              </thead>
              <tbody>
                <tr v-if="localNetAdapters.length === 0"><td colspan="5" class="empty">局域网适配器未启用（默认关闭）</td></tr>
                <tr v-for="adapter in localNetAdapters" :key="adapter.adapterId">
                  <td class="mono">{{ adapter.adapterId }}</td>
                  <td>{{ adapter.protocolFamily ?? '—' }}</td>
                  <td>
                    <StatusBadge :tone="adapter.running ? 'ok' : adapter.enabled ? 'warn' : 'neutral'"
                      :label="`${adapter.configured ? '已配置' : '未配置'} · ${adapter.enabled ? '已启用' : '未启用'} · ${adapter.running ? '运行中' : '未运行'}`" />
                  </td>
                  <td class="num">{{ adapter.receivedCount ?? 0 }} / {{ adapter.sentCount ?? 0 }} / {{ adapter.errorCount ?? 0 }}</td>
                  <td class="small">{{ adapter.lastError ?? '—' }}</td>
                </tr>
              </tbody>
            </table>
          </div>
        </Panel>
      </div>
    </template>
  </div>
</template>

<style scoped>
.ops-view {
  display: flex;
  flex-direction: column;
  gap: var(--gap-lg);
}

.tab-bar {
  display: flex;
  gap: 4px;
  background: var(--bg-panel);
  border: 1px solid var(--border);
  border-radius: var(--radius-lg);
  padding: 4px;
  width: fit-content;
}

.tab {
  border: none;
  background: none;
  color: var(--text-secondary);
  font-size: var(--fs-sm);
  font-weight: 600;
  padding: 6px 16px;
  border-radius: var(--radius-md);
  cursor: pointer;
}

.tab:hover { color: var(--text-primary); }
.tab.active { background: var(--accent-muted); color: var(--accent); }

.alarm-table { max-height: 60vh; }
.detail-text { margin: 2px 0 0; font-size: var(--fs-xs); color: var(--text-secondary); }

.runs-grid, .health-grid {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1.2fr);
  gap: var(--gap-lg);
  align-items: start;
}

@media (max-width: 1100px) {
  .runs-grid, .health-grid { grid-template-columns: 1fr; }
}

.runs-table { max-height: 50vh; }
.audit-table { max-height: 60vh; }
.localnet-table { max-height: 40vh; }

.summary-block {
  max-height: 50vh;
  padding: 12px 14px;
}

.summary-block pre {
  margin: 0;
  font-size: var(--fs-xs);
  color: var(--text-secondary);
  white-space: pre-wrap;
  word-break: break-all;
}

.health-cards { display: flex; flex-direction: column; gap: var(--gap-md); }

.health-card {
  background: var(--bg-panel-raised);
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  padding: 10px 12px;
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.health-card header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 8px;
}

.reject-list {
  margin: 4px 0 0;
  padding-left: 18px;
  font-size: var(--fs-xs);
  color: var(--status-warn);
}

.error-line {
  color: var(--status-danger);
  font-size: var(--fs-xs);
  padding: 8px 14px;
  margin: 0;
}

.small { font-size: var(--fs-xs); }
.muted { color: var(--text-muted); }
.empty { text-align: center; color: var(--text-muted); padding: 14px; }
</style>
