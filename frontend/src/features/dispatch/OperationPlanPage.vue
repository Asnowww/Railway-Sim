<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { storeToRefs } from 'pinia'
import { dispatchApi } from '../../api/dispatch'
import { simulationApi } from '../../api/rest'
import { useSimulationStore } from '../../stores/simulation'
import Panel from '../../shared/components/Panel.vue'
import StatusBadge from '../../shared/components/StatusBadge.vue'
import { toastError, toastOk } from '../../shared/toast'
import type {
  DispatchCirculationPlan,
  DispatchCirculationLeg,
  RunPlanPeriod,
  RunPlanResponse,
  TrainServicePlan
} from '../../types/dispatch'
import type { TrainState } from '../../types/simulation'

const simulation = useSimulationStore()
const { dispatch, trains } = storeToRefs(simulation)

const plan = ref<RunPlanResponse | null>(null)
const loading = ref(false)
const autoAssigning = ref(false)
const loadError = ref('')

onMounted(() => {
  void loadPlan()
})

async function loadPlan(): Promise<void> {
  loading.value = true
  try {
    plan.value = await dispatchApi.plan()
    loadError.value = ''
  } catch (error) {
    loadError.value = error instanceof Error ? error.message : String(error)
  } finally {
    loading.value = false
  }
}

async function autoAssignCirculations(): Promise<void> {
  autoAssigning.value = true
  try {
    const created = await dispatchApi.autoAssignCirculationPlans({
      cycleTarget: 2,
      headwaySeconds: dispatch.value.targetHeadwaySeconds || 300,
      leadSeconds: 0
    })
    toastOk('已按车辆起点补扫交路', `新增 ${created.length} 个车队交路`)
    simulation.applySnapshot(await simulationApi.snapshot())
  } catch (error) {
    toastError('车队交路补扫失败', error)
  } finally {
    autoAssigning.value = false
  }
}

const currentPeriod = computed<RunPlanPeriod | null>(() =>
  plan.value?.periods.find((period) => period.periodType === dispatch.value.runMode) ?? plan.value?.periods[0] ?? null
)

const orderedServices = computed(() =>
  [...(plan.value?.services ?? [])].sort((left, right) => {
    const leftDeparture = firstStop(left)?.departureOffsetSec ?? Number.MAX_SAFE_INTEGER
    const rightDeparture = firstStop(right)?.departureOffsetSec ?? Number.MAX_SAFE_INTEGER
    return leftDeparture - rightDeparture
  })
)

const circulationPlans = computed(() => dispatch.value.circulationPlans ?? [])

const onlineTrainIds = computed(() => new Set(trains.value.map((train) => train.id)))

const trainById = computed(() => new Map(trains.value.map((train) => [train.id, train])))

const circulationByTrain = computed(() =>
  new Map(circulationPlans.value.map((item) => [item.trainId, item]))
)

const operationPlanByLegId = computed(() =>
  new Map(
    dispatch.value.operationPlans
      .filter((item) => item.circulationLegId)
      .map((item) => [item.circulationLegId as string, item])
  )
)

const unassignedTrainCount = computed(() =>
  trains.value.filter((train) => !circulationByTrain.value.has(train.id)).length
)

const displayedPlanId = computed(() => (plan.value?.planId ?? dispatch.value.planId) || '—')

interface DynamicTimetableRow {
  key: string
  trainId: string
  online: boolean
  positionText: string
  startTerminalId: string | null
  routeId: string | null
  routeName: string | null
  direction: string | null
  fromPointId: string | null
  toPointId: string | null
  plannedDepartureAt: string | null
  sequence: number | null
  cycleText: string
  circulationStatus: string
  routeStatus: string
  rejectReason: string | null
  assignmentHint: string
}

const dynamicTimetableRows = computed<DynamicTimetableRow[]>(() => {
  const rows: DynamicTimetableRow[] = []
  for (const train of trains.value) {
    rows.push(dynamicRowForTrain(train, circulationByTrain.value.get(train.id) ?? null))
  }
  for (const circulation of circulationPlans.value) {
    if (!trainById.value.has(circulation.trainId)) {
      rows.push(dynamicRowForCirculation(circulation))
    }
  }

  const grouped = new Map<string, DynamicTimetableRow[]>()
  for (const row of rows) {
    if (!row.plannedDepartureAt || !row.routeId) continue
    const groupKey = `${row.startTerminalId || row.fromPointId || 'UNKNOWN'}-${row.routeId}`
    const group = grouped.get(groupKey) ?? []
    group.push(row)
    grouped.set(groupKey, group)
  }
  for (const group of grouped.values()) {
    group
      .sort((left, right) => (left.plannedDepartureAt ?? '').localeCompare(right.plannedDepartureAt ?? ''))
      .forEach((row, index) => {
        row.sequence = index + 1
      })
  }

  return rows.sort((left, right) => {
    const leftTime = left.plannedDepartureAt ?? '9999'
    const rightTime = right.plannedDepartureAt ?? '9999'
    if (leftTime !== rightTime) return leftTime.localeCompare(rightTime)
    return left.trainId.localeCompare(right.trainId)
  })
})

function firstStop(service: TrainServicePlan) {
  return service.stops[0] ?? null
}

function lastStop(service: TrainServicePlan) {
  return service.stops[service.stops.length - 1] ?? null
}

function formatOffset(seconds: number | null | undefined): string {
  if (seconds === null || seconds === undefined || Number.isNaN(seconds)) return '—'
  const rounded = Math.max(0, Math.round(seconds))
  const minutes = Math.floor(rounded / 60)
  const remain = rounded % 60
  return `T+${minutes}:${remain.toString().padStart(2, '0')}`
}

function formatDateTime(value?: string | null): string {
  if (!value) return '—'
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString('zh-CN', { hour12: false })
}

function modeLabel(mode?: string | null): string {
  const labels: Record<string, string> = {
    PEAK: '高峰',
    FLAT: '平峰',
    OFF_PEAK: '低谷'
  }
  return labels[mode ?? ''] ?? (mode || '—')
}

function directionLabel(direction?: string | null): string {
  const labels: Record<string, string> = {
    UP: '上行',
    DOWN: '下行'
  }
  return labels[direction ?? ''] ?? (direction || '—')
}

function terminalLabel(stationId?: string | null): string {
  const labels: Record<string, string> = {
    S101: '郭公庄',
    S113: '国家图书馆',
    U_START: '郭公庄上行端',
    D_START: '国图下行端'
  }
  return stationId ? labels[stationId] ?? stationId : '—'
}

function circulationStatusLabel(status: string): string {
  const labels: Record<string, string> = {
    ASSIGNED: '已分配',
    WAITING_ROUTE: '等进路',
    IN_SERVICE: '运行中',
    BLOCKED: '受阻',
    RESTING: '休息',
    CANCELLED: '已取消'
  }
  return labels[status] ?? status
}

function currentLeg(plan: DispatchCirculationPlan) {
  return plan.legs[plan.currentLegPointer] ?? null
}

function dynamicRowForTrain(train: TrainState, circulation: DispatchCirculationPlan | null): DynamicTimetableRow {
  if (!circulation) {
    return {
      key: `train-${train.id}`,
      trainId: train.id,
      online: true,
      positionText: trainPositionText(train),
      startTerminalId: inferTerminalFromTrain(train),
      routeId: null,
      routeName: null,
      direction: train.direction,
      fromPointId: null,
      toPointId: null,
      plannedDepartureAt: null,
      sequence: null,
      cycleText: '—',
      circulationStatus: 'UNASSIGNED',
      routeStatus: '—',
      rejectReason: null,
      assignmentHint: assignmentHint(train)
    }
  }
  return dynamicRowForCirculation(circulation, train)
}

function dynamicRowForCirculation(circulation: DispatchCirculationPlan, train?: TrainState): DynamicTimetableRow {
  const leg = currentLeg(circulation)
  const operationPlan = leg?.operationPlanId ? operationPlanByLegId.value.get(leg.legId) : null
  return {
    key: `circulation-${circulation.circulationId}`,
    trainId: circulation.trainId,
    online: Boolean(train),
    positionText: train ? trainPositionText(train) : '未在快照',
    startTerminalId: circulation.startTerminalId,
    routeId: leg?.routeId ?? null,
    routeName: leg?.routeName ?? null,
    direction: leg?.direction ?? null,
    fromPointId: leg?.fromPointId ?? null,
    toPointId: leg?.toPointId ?? null,
    plannedDepartureAt: operationPlan?.plannedDepartureAt ?? leg?.plannedDepartureAt ?? circulation.plannedStartAt ?? null,
    sequence: null,
    cycleText: `${circulation.cycleCompleted}/${circulation.cycleTarget} · leg ${(leg?.legIndex ?? circulation.currentLegPointer) + 1}`,
    circulationStatus: circulation.status,
    routeStatus: operationPlan?.status ?? leg?.status ?? '—',
    rejectReason: operationPlan?.rejectReason ?? leg?.rejectReason ?? null,
    assignmentHint: leg
      ? `${terminalLabel(leg.fromPointId)} → ${terminalLabel(leg.toPointId)}`
      : '已建交路，等待当前路线'
  }
}

function trainPositionText(train: TrainState): string {
  if (train.currentStationId) return terminalLabel(train.currentStationId)
  return `${Math.round(train.positionMeters)}m`
}

function inferTerminalFromTrain(train: TrainState): string | null {
  if (train.currentStationId === 'S101' || train.currentStationId === 'S113') return train.currentStationId
  if (train.positionMeters <= 150) return 'S101'
  if (train.positionMeters >= 11850) return 'S113'
  return null
}

function assignmentHint(train: TrainState): string {
  const terminal = inferTerminalFromTrain(train)
  if (terminal) return `可按 ${terminalLabel(terminal)} 起点补交路`
  if (train.faultLevel > 1) return '车辆快照 faultLevel > 1，暂不自动分配'
  return '不在 S101/S113 始发端点，等待到端点后接入'
}

function dynamicStatusLabel(status: string): string {
  if (status === 'UNASSIGNED') return '待分配'
  return circulationStatusLabel(status)
}

function dynamicStatusTone(status: string) {
  if (status === 'UNASSIGNED') return 'warn' as const
  if (status === 'BLOCKED' || status === 'CANCELLED') return 'warn' as const
  if (status === 'RESTING') return 'neutral' as const
  return 'info' as const
}

function routeStatusLabel(status?: string | null): string {
  const labels: Record<string, string> = {
    PLANNED: '已计划',
    ROUTE_REQUESTED: '已申请进路',
    ROUTE_ACCEPTED: '进路已接受',
    ROUTE_REJECTED: '进路被拒',
    CANCELLED: '已取消',
    WAITING_ROUTE: '等进路',
    IN_SERVICE: '运行中',
    BLOCKED: '受阻',
    RESTING: '休息'
  }
  return status ? labels[status] ?? status : '—'
}
</script>

<template>
  <div class="operation-plan-page">
    <div class="plan-strip">
      <article>
        <span>计划</span>
        <strong>{{ displayedPlanId }}</strong>
        <small>{{ plan?.lineId ?? '等待计划' }}</small>
      </article>
      <article>
        <span>当前时段</span>
        <strong>{{ modeLabel(dispatch.runMode) }}</strong>
        <small>{{ currentPeriod ? `${currentPeriod.start}-${currentPeriod.end}` : '—' }}</small>
      </article>
      <article>
        <span>发车间隔</span>
        <strong>{{ dispatch.targetHeadwaySeconds || currentPeriod?.departureIntervalSec || 0 }}s</strong>
        <small>默认停站 {{ dispatch.defaultDwellSeconds || currentPeriod?.defaultDwellTimeSec || 0 }}s</small>
      </article>
      <article>
        <span>实时车辆</span>
        <strong>{{ trains.length }}</strong>
        <small>待分配 {{ unassignedTrainCount }} 列</small>
      </article>
      <article>
        <span>已分配交路</span>
        <strong>{{ circulationPlans.length }}</strong>
        <small>动态时刻 {{ dynamicTimetableRows.length }} 行</small>
      </article>
    </div>

    <Panel title="运营计划来源" subtitle="动态车队时刻表来自实时车辆快照；基础模板仅来自后端配置">
      <template #actions>
        <button type="button" class="btn sm" :disabled="loading" @click="loadPlan">刷新计划</button>
        <button type="button" class="btn primary sm" :disabled="autoAssigning" @click="autoAssignCirculations">
          {{ autoAssigning ? '生成中' : '为端点车辆生成交路' }}
        </button>
      </template>
      <div class="publish-grid">
        <div>
          <span>计划来源</span>
          <strong>后端配置</strong>
        </div>
        <div>
          <span>实时车辆</span>
          <strong>{{ trains.length }} 列</strong>
        </div>
        <div>
          <span>交路计划</span>
          <strong>{{ circulationPlans.length }} 个</strong>
        </div>
        <div>
          <span>绑定规则</span>
          <strong>只接入端点车辆</strong>
        </div>
      </div>
      <p v-if="loadError" class="load-error">{{ loadError }}</p>
    </Panel>

    <div class="plan-grid">
      <Panel title="动态车队时刻表" :subtitle="`${dynamicTimetableRows.length} 辆实时/计划车辆`" flush>
        <div class="table-scroll">
          <table class="data">
            <thead>
              <tr>
                <th>列车</th>
                <th>当前位置</th>
                <th>始发端</th>
                <th>当前路线</th>
                <th>方向</th>
                <th>计划发车</th>
                <th>间隔序号</th>
                <th>交路</th>
                <th>进路计划</th>
              </tr>
            </thead>
            <tbody>
              <tr v-if="dynamicTimetableRows.length === 0">
                <td colspan="9" class="empty">暂无实时车辆。等待信号/轨道侧创建车辆并进入快照。</td>
              </tr>
              <tr v-for="row in dynamicTimetableRows" :key="row.key">
                <td>
                  <strong class="mono">{{ row.trainId }}</strong>
                  <small>{{ row.online ? '在线' : '计划保留' }}</small>
                </td>
                <td>{{ row.positionText }}</td>
                <td>{{ terminalLabel(row.startTerminalId) }}</td>
                <td>
                  <strong>{{ row.routeId ?? '待分配' }}</strong>
                  <small>{{ row.routeName ?? row.assignmentHint }}</small>
                </td>
                <td>{{ directionLabel(row.direction) }}</td>
                <td>{{ formatDateTime(row.plannedDepartureAt) }}</td>
                <td>{{ row.sequence ? `第 ${row.sequence} 辆` : '—' }}</td>
                <td>
                  <StatusBadge
                    :tone="dynamicStatusTone(row.circulationStatus)"
                    :label="dynamicStatusLabel(row.circulationStatus)"
                  />
                  <small>{{ row.cycleText }}</small>
                </td>
                <td>
                  <strong>{{ routeStatusLabel(row.routeStatus) }}</strong>
                  <small>{{ row.rejectReason || row.assignmentHint }}</small>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </Panel>

      <Panel title="基础车次模板（配置参考）" :subtitle="`${orderedServices.length} 个后端配置车次`" flush>
        <div class="table-scroll compact">
          <table class="data">
            <thead>
              <tr>
                <th>车次</th>
                <th>列车</th>
                <th>方向</th>
                <th>始发</th>
                <th>终到</th>
                <th>计划发车</th>
              </tr>
            </thead>
            <tbody>
              <tr v-if="orderedServices.length === 0">
                <td colspan="6" class="empty">暂无配置车次模板</td>
              </tr>
              <tr v-for="service in orderedServices" :key="service.serviceId">
                <td class="mono">{{ service.serviceId }}</td>
                <td class="mono">{{ service.trainId }}</td>
                <td>{{ directionLabel(service.direction) }}</td>
                <td>{{ terminalLabel(firstStop(service)?.stationId) }}</td>
                <td>{{ terminalLabel(lastStop(service)?.stationId) }}</td>
                <td>{{ formatOffset(firstStop(service)?.departureOffsetSec) }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </Panel>
    </div>

    <Panel title="车队交路循环" subtitle="车辆由轨道侧创建，调度侧按 S101/S113 或端点米标自动分配 M9_LOOP">
      <div class="circulation-list">
        <article v-if="circulationPlans.length === 0" class="empty-card">
          暂无车队交路。端点车辆进入快照后会自动生成，也可点击上方“补扫车队交路”。
        </article>
        <article v-for="item in circulationPlans" :key="item.circulationId" class="circulation-card">
          <header>
            <strong>{{ item.trainId }} · {{ terminalLabel(item.startTerminalId) }}</strong>
            <StatusBadge
              :tone="item.status === 'BLOCKED' ? 'warn' : item.status === 'RESTING' ? 'neutral' : 'info'"
              :label="circulationStatusLabel(item.status)"
            />
          </header>
          <dl>
            <div>
              <dt>当前路线</dt>
              <dd>{{ currentLeg(item)?.routeId ?? '—' }}</dd>
            </div>
            <div>
              <dt>循环进度</dt>
              <dd>{{ item.cycleCompleted }} / {{ item.cycleTarget }}</dd>
            </div>
            <div>
              <dt>间隔</dt>
              <dd>{{ item.headwaySeconds }}s</dd>
            </div>
            <div>
              <dt>下一发车</dt>
              <dd>{{ formatDateTime(currentLeg(item)?.plannedDepartureAt ?? item.plannedStartAt) }}</dd>
            </div>
          </dl>
          <div class="leg-strip">
            <span
              v-for="leg in item.legs.slice(0, 8)"
              :key="leg.legId"
              :data-active="leg.legIndex === item.currentLegPointer"
            >
              {{ leg.routeId }}
            </span>
          </div>
        </article>
      </div>
    </Panel>
  </div>
</template>

<style scoped>
.operation-plan-page {
  display: grid;
  gap: var(--gap-lg);
}

.plan-strip {
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
  gap: var(--gap-lg);
}

.plan-strip article {
  display: grid;
  gap: 4px;
  min-height: 88px;
  padding: 12px 14px;
  border: 1px solid var(--border);
  border-radius: var(--radius-lg);
  background: var(--bg-panel);
  box-shadow: var(--shadow-panel);
}

.plan-strip span,
.plan-strip small,
.publish-grid span,
.circulation-card dt {
  color: var(--text-secondary);
  font-size: var(--fs-xs);
}

.plan-strip strong {
  font-size: var(--fs-xl);
}

.publish-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: var(--gap-md);
}

.publish-grid > div {
  display: grid;
  gap: 6px;
  min-height: 64px;
  padding: 10px 12px;
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  background: var(--bg-panel-raised);
}

.plan-grid {
  display: grid;
  grid-template-columns: minmax(0, 1.35fr) minmax(360px, 0.65fr);
  gap: var(--gap-lg);
}

.table-scroll {
  max-height: 460px;
  overflow: auto;
}

.table-scroll.compact {
  max-height: 360px;
}

.data td strong {
  display: block;
}

.data td small {
  display: block;
  margin-top: 3px;
  color: var(--text-secondary);
  font-size: var(--fs-xs);
  white-space: normal;
}

.circulation-list {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(300px, 1fr));
  gap: var(--gap-md);
}

.circulation-card,
.empty-card {
  display: grid;
  gap: var(--gap-md);
  padding: 12px;
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  background: var(--bg-panel-raised);
}

.circulation-card header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--gap-sm);
}

.circulation-card dl {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: var(--gap-sm);
  margin: 0;
}

.circulation-card div {
  min-width: 0;
}

.circulation-card dd {
  margin: 2px 0 0;
  overflow-wrap: anywhere;
}

.leg-strip {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.leg-strip span {
  padding: 3px 8px;
  border: 1px solid var(--border);
  border-radius: 999px;
  background: var(--bg-inset);
  color: var(--text-secondary);
  font-size: var(--fs-xs);
}

.leg-strip span[data-active='true'] {
  color: var(--status-info);
  border-color: var(--status-info);
  background: var(--status-info-bg);
}

.load-error {
  color: var(--status-danger);
}

.empty,
.empty-card {
  color: var(--text-muted);
}

@media (max-width: 1100px) {
  .plan-strip,
  .publish-grid,
  .plan-grid {
    grid-template-columns: 1fr;
  }
}

@media (min-width: 1101px) and (max-width: 1500px) {
  .plan-strip {
    gap: var(--gap-md);
  }

  .plan-strip article {
    padding: 10px 12px;
  }

  .plan-strip strong {
    font-size: var(--fs-lg);
  }
}
</style>
