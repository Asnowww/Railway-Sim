<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { storeToRefs } from 'pinia'
import { useSimulationStore } from '../../stores/simulation'
import { useConnectionStore } from '../../stores/connection'
import { useTopologyStore } from '../../stores/topology'
import { alarmApi } from '../../api/alarms'
import Panel from '../../shared/components/Panel.vue'
import StatusBadge from '../../shared/components/StatusBadge.vue'
import LineDiagram, { type DiagramLayers } from './LineDiagram.vue'
import {
  alarmLevelLabel,
  alarmLevelTone,
  occupancyLabel,
  occupancyTone,
  operationModeLabel,
  trainStatusLabel
} from '../../shared/labels'
import { toastError, toastOk } from '../../shared/toast'

const router = useRouter()
const simulation = useSimulationStore()
const connection = useConnectionStore()
const topology = useTopologyStore()

const { trains, trackSegments, authorities, signalStates, switchStates, routeStates, powerSections, alarms, dispatch } =
  storeToRefs(simulation)

const layers = reactive<DiagramLayers>({ track: true, trains: true, signals: true, switches: true, ma: true, power: true })
const layerLabels: Record<keyof DiagramLayers, string> = {
  track: '轨道',
  trains: '列车',
  signals: '信号',
  switches: '道岔',
  ma: '移动授权',
  power: '供电'
}

const selectedRouteId = ref('')
const highlightSegments = computed(() => {
  if (!selectedRouteId.value) return null
  const route = routeStates.value.find((item) => item.routeId === selectedRouteId.value)
  return route ? route.axleSegmentIds : null
})

/* 关键指标：只展示可从真实数据推导的值 */
const openDisturbances = computed(() => dispatch.value.openDisturbances.filter((item) => item.status === 'OPEN'))
const occupiedCount = computed(() => trackSegments.value.filter((segment) => segment.occupancy === 'OCCUPIED').length)
const faultSegmentCount = computed(
  () => trackSegments.value.filter((segment) => segment.occupancy === 'FAULT' || segment.occupancy === 'BLOCKED').length
)
const averageHeadway = computed(() => {
  const values = dispatch.value.stationHeadways
    .map((item) => item.actualHeadwaySeconds)
    .filter((value) => Number.isFinite(value) && value > 0)
  if (values.length === 0) return null
  return Math.round(values.reduce((sum, value) => sum + value, 0) / values.length)
})

const sortedAlarms = computed(() =>
  [...alarms.value].sort((a, b) => {
    if (a.confirmed !== b.confirmed) return a.confirmed ? 1 : -1
    return b.level - a.level
  })
)

const acknowledging = ref<string | null>(null)

async function acknowledge(alarmId: string): Promise<void> {
  acknowledging.value = alarmId
  try {
    await alarmApi.acknowledge(alarmId, 'dispatcher-01')
    toastOk('告警已确认', alarmId)
  } catch (error) {
    toastError('告警确认失败', error)
  } finally {
    acknowledging.value = null
  }
}

function openTrain(trainId: string): void {
  void router.push({ path: '/vehicles', query: { train: trainId } })
}

function openSegment(segmentId: string): void {
  void router.push({ path: '/signal-track', query: { segment: segmentId } })
}

function formatTime(iso: string): string {
  const date = new Date(iso)
  return Number.isNaN(date.getTime()) ? iso : date.toLocaleTimeString('zh-CN', { hour12: false })
}

const stale = computed(() => !connection.dataTrusted)
</script>

<template>
  <div class="overview">
    <div class="metric-row" role="list" aria-label="关键运行指标">
      <div class="metric" role="listitem">
        <span class="metric-label">在线列车</span>
        <strong class="num">{{ trains.length }}</strong>
      </div>
      <div class="metric" role="listitem">
        <span class="metric-label">占用区段</span>
        <strong class="num">{{ occupiedCount }}<small>/{{ trackSegments.length }}</small></strong>
      </div>
      <div :class="['metric', { alert: faultSegmentCount > 0 }]" role="listitem">
        <span class="metric-label">故障区段</span>
        <strong class="num">{{ faultSegmentCount }}</strong>
      </div>
      <div class="metric" role="listitem">
        <span class="metric-label">目标间隔</span>
        <strong class="num">{{ dispatch.targetHeadwaySeconds || '—' }}<small>s</small></strong>
      </div>
      <div class="metric" role="listitem">
        <span class="metric-label">实际平均间隔</span>
        <strong class="num">{{ averageHeadway ?? '—' }}<small v-if="averageHeadway !== null">s</small></strong>
      </div>
      <div :class="['metric', { alert: openDisturbances.length > 0 }]" role="listitem">
        <span class="metric-label">开放扰动</span>
        <strong class="num">{{ openDisturbances.length }}</strong>
      </div>
      <div :class="['metric', { alert: simulation.unconfirmedAlarmCount > 0 }]" role="listitem">
        <span class="metric-label">未确认告警</span>
        <strong class="num">{{ simulation.unconfirmedAlarmCount }}</strong>
      </div>
    </div>

    <div class="main-grid">
      <Panel
        title="线路综合态势图"
        :subtitle="topology.topology ? `${topology.topology.lineName ?? topology.topology.lineId} · ${Math.round(topology.lineLengthMeters)}m · ${trackSegments.length} 区段` : '等待拓扑数据…'"
        :stale="stale"
        :stale-since="connection.lastSnapshotClock"
      >
        <template #actions>
          <div class="layer-toggles" role="group" aria-label="图层开关">
            <label v-for="(label, key) in layerLabels" :key="key" class="layer-toggle">
              <input v-model="layers[key]" type="checkbox" />
              {{ label }}
            </label>
          </div>
          <select v-model="selectedRouteId" aria-label="进路高亮">
            <option value="">无进路高亮</option>
            <option v-for="route in routeStates" :key="route.routeId" :value="route.routeId">
              {{ route.routeId }}（{{ route.status }}）
            </option>
          </select>
        </template>

        <div v-if="topology.error" class="topo-error">
          拓扑加载失败：{{ topology.error }}
          <button type="button" class="btn sm" @click="topology.load(true)">重试</button>
        </div>
        <LineDiagram
          v-else
          :segments="trackSegments.length > 0 ? trackSegments : topology.topology?.segments ?? []"
          :stations="topology.stations"
          :trains="trains"
          :authorities="authorities"
          :signals="signalStates"
          :switches="switchStates"
          :power-sections="powerSections"
          :layers="layers"
          :highlight-segment-ids="highlightSegments"
          :height="320"
          @select-train="openTrain"
          @select-segment="openSegment"
        />
      </Panel>

      <Panel title="实时告警" :subtitle="`${simulation.unconfirmedAlarmCount} 条未确认`" flush>
        <div class="alarm-feed scroll-y">
          <p v-if="sortedAlarms.length === 0" class="empty">当前无告警</p>
          <article v-for="alarm in sortedAlarms" :key="alarm.id" :class="['alarm-item', { confirmed: alarm.confirmed }]">
            <header>
              <StatusBadge
                :tone="alarmLevelTone(alarm.level)"
                :label="alarmLevelLabel(alarm.level)"
                :blink="alarm.level >= 3 && !alarm.confirmed"
              />
              <span class="alarm-src">{{ alarm.sourceModule }} · {{ alarm.locationRef }}</span>
              <time class="num">{{ formatTime(alarm.raisedAt) }}</time>
            </header>
            <p class="alarm-title">{{ alarm.title }}</p>
            <p class="alarm-detail">{{ alarm.detail }}</p>
            <footer>
              <button
                type="button"
                class="btn sm"
                :disabled="alarm.confirmed || acknowledging === alarm.id"
                @click="acknowledge(alarm.id)"
              >
                {{ alarm.confirmed ? '已确认' : '确认' }}
              </button>
            </footer>
          </article>
        </div>
      </Panel>
    </div>

    <Panel title="列车状态一览" flush :stale="stale" :stale-since="connection.lastSnapshotClock">
      <div class="scroll-y table-wrap">
        <table class="data">
          <thead>
            <tr>
              <th>列车</th>
              <th>车次</th>
              <th>状态</th>
              <th>模式</th>
              <th>速度</th>
              <th>里程</th>
              <th>所在区段</th>
              <th>MA 余量</th>
              <th>限速</th>
              <th>满载率</th>
              <th>故障</th>
            </tr>
          </thead>
          <tbody>
            <tr v-if="trains.length === 0">
              <td colspan="11" class="empty">暂无列车（后端未运行或未注册车辆）</td>
            </tr>
            <tr v-for="train in trains" :key="train.id" class="clickable" @click="openTrain(train.id)">
              <td class="mono">{{ train.id }}</td>
              <td class="mono">{{ train.serviceNo || '—' }}</td>
              <td>{{ trainStatusLabel(train.status) }}</td>
              <td>{{ operationModeLabel(train.operationMode) }}</td>
              <td class="num">{{ Math.round(train.speedMetersPerSecond * 3.6) }} km/h</td>
              <td class="num">{{ Math.round(train.positionMeters) }} m</td>
              <td class="mono">{{ authorities.find((a) => a.trainId === train.id)?.currentSegmentId || '—' }}</td>
              <td class="num">{{ Math.round(train.movementAuthorityDistanceMeters) }} m</td>
              <td class="num">{{ Math.round(train.speedLimitMetersPerSecond * 3.6) }} km/h</td>
              <td class="num">{{ Math.round(train.loadRate * 100) }}%</td>
              <td>
                <StatusBadge
                  v-if="train.faultCode && train.faultCode !== 'OK'"
                  tone="danger"
                  :label="train.faultCode"
                />
                <span v-else class="ok-text">正常</span>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </Panel>

    <Panel v-if="trackSegments.length > 0" title="区段占用一览（态势图等价数据）" flush>
      <div class="segment-strip scroll-y">
        <button
          v-for="segment in trackSegments"
          :key="segment.id"
          type="button"
          class="segment-chip"
          @click="openSegment(segment.id)"
        >
          <span class="mono">{{ segment.id }}</span>
          <StatusBadge :tone="occupancyTone(segment.occupancy)" :label="occupancyLabel(segment.occupancy)" />
        </button>
      </div>
    </Panel>
  </div>
</template>

<style scoped>
.overview {
  display: flex;
  flex-direction: column;
  gap: var(--gap-lg);
}

.metric-row {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(130px, 1fr));
  gap: var(--gap-md);
}

.metric {
  background: var(--bg-panel);
  border: 1px solid var(--border);
  border-radius: var(--radius-lg);
  padding: 10px 14px;
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.metric.alert {
  border-color: var(--status-danger);
  background: var(--status-danger-bg);
}

.metric-label {
  font-size: var(--fs-xs);
  color: var(--text-secondary);
}

.metric strong {
  font-size: var(--fs-xl);
  line-height: 1.1;
}

.metric small {
  font-size: var(--fs-sm);
  color: var(--text-muted);
  margin-left: 2px;
}

.main-grid {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 330px;
  gap: var(--gap-lg);
  align-items: stretch;
}

@media (max-width: 1200px) {
  .main-grid {
    grid-template-columns: 1fr;
  }
}

.layer-toggles {
  display: flex;
  gap: var(--gap-sm);
  flex-wrap: wrap;
}

.layer-toggle {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: var(--fs-xs);
  color: var(--text-secondary);
  cursor: pointer;
}

.topo-error {
  color: var(--status-danger);
  font-size: var(--fs-sm);
  display: flex;
  align-items: center;
  gap: var(--gap-md);
}

.alarm-feed {
  max-height: 360px;
  display: flex;
  flex-direction: column;
}

.alarm-item {
  padding: 10px 14px;
  border-bottom: 1px solid var(--border);
}

.alarm-item.confirmed {
  opacity: 0.55;
}

.alarm-item header {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 4px;
}

.alarm-src {
  font-size: var(--fs-xs);
  color: var(--text-secondary);
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.alarm-item time {
  font-size: var(--fs-xs);
  color: var(--text-muted);
}

.alarm-title {
  margin: 0;
  font-size: var(--fs-sm);
  font-weight: 600;
}

.alarm-detail {
  margin: 2px 0 6px;
  font-size: var(--fs-xs);
  color: var(--text-secondary);
}

.alarm-item footer {
  display: flex;
  justify-content: flex-end;
}

.empty {
  padding: 20px;
  text-align: center;
  color: var(--text-muted);
  font-size: var(--fs-sm);
}

.table-wrap {
  max-height: 300px;
}

.ok-text {
  color: var(--text-muted);
  font-size: var(--fs-xs);
}

.segment-strip {
  display: flex;
  flex-wrap: wrap;
  gap: var(--gap-sm);
  padding: 12px 14px;
  max-height: 140px;
}

.segment-chip {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  background: var(--bg-panel-raised);
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  padding: 3px 8px;
  color: var(--text-primary);
  cursor: pointer;
  font-size: var(--fs-xs);
}

.segment-chip:hover {
  border-color: var(--accent);
}
</style>
