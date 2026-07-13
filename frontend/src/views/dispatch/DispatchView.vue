<script setup lang="ts">
import { computed } from 'vue'
import CommandTimeline from '../../components/dispatch/CommandTimeline.vue'
import DisturbanceList from '../../components/dispatch/DisturbanceList.vue'
import type {
  DispatchCommandView,
  DispatchSnapshot,
  PlannedStop,
  RunPlanPeriod,
  RunPlanResponse,
  TrainServicePlan
} from '../../types/dispatch'
import type { SimulationSnapshot, TrainState } from '../../types/simulation'

const props = defineProps<{
  plan: RunPlanResponse | null
  snapshot: SimulationSnapshot | null
  dispatch: DispatchSnapshot
}>()

const modeLabel = (mode: string) => {
  const labels: Record<string, string> = {
    PEAK: '高峰',
    FLAT: '平峰',
    OFF_PEAK: '低谷'
  }
  return labels[mode] ?? mode
}

const directionLabel = (direction: string) => {
  const labels: Record<string, string> = {
    DOWN: '下行',
    UP: '上行'
  }
  return labels[direction] ?? direction
}

const commandLabel = (type: string) => {
  const labels: Record<string, string> = {
    SHORTEN_DWELL: '缩短停站',
    EXTEND_DWELL: '延长停站',
    HEADWAY_ADJUST: '间隔调节',
    SPEED_BIAS: '速度偏置',
    SPEED_LIMIT: '临时限速',
    TEMP_SPEED_LIMIT: '临时限速',
    HOLD: '扣车',
    HOLD_TRAIN: '扣车',
    DEPART: '发车',
    REQUEST_ROUTE: '申请进路',
    REROUTE: '重排进路'
  }
  return labels[type] ?? type
}

const commandStatusLabel = (status: string) => {
  const labels: Record<string, string> = {
    PENDING: '待下发',
    SENT: '已下发',
    APPLIED: '执行中',
    EFFECT_CONFIRMED: '效果已确认',
    TIMEOUT: '超时',
    CANCELLED: '已取消',
    EXPIRED: '已结束',
    RELEASED: '已释放'
  }
  return labels[status] ?? status
}

const trainStatusLabel = (status: string) => {
  const labels: Record<string, string> = {
    RUNNING: '运行',
    DWELLING: '停站',
    EMERGENCY_BRAKE: '紧急制动',
    DEGRADED: '降级',
    FAULT: '故障'
  }
  return labels[status] ?? status
}

const dynamicsReasonLabel = (reason: string) => {
  const labels: Record<string, string> = {
    STATION_STOP_WINDOW: '站停窗口',
    STATION_APPROACH: '进站制动',
    MA_DISTANCE_LIMIT: '移动授权距离限制',
    MOVEMENT_AUTHORITY_EXHAUSTED: '移动授权已用尽',
    SPEED_LIMIT_EXCEEDED: '超过限速',
    SPEED_MARGIN_AVAILABLE: '可牵引加速',
    NEAR_TARGET_SPEED: '接近目标速度',
    TARGET_SPEED_REACHED: '达到目标速度',
    TRACTION_UNAVAILABLE: '牵引不可用'
  }
  return labels[reason] ?? reason
}

const regulationLabel = (action: string) => {
  const labels: Record<string, string> = {
    CATCH_UP: '追赶',
    SLOW_DOWN: '放慢',
    NORMAL: '正常',
    OBSERVE: '观察'
  }
  return labels[action] ?? action
}

const headwayStateLabel = (state: string) => {
  const labels: Record<string, string> = {
    NORMAL: '正常',
    TOO_SHORT: '间隔过小',
    TOO_LONG: '间隔过大',
    SCHEDULE_LATE: '运行图晚点',
    UNKNOWN: '待观测'
  }
  return labels[state] ?? state
}

const formatSeconds = (seconds: number | null | undefined) => {
  if (seconds === null || seconds === undefined || Number.isNaN(seconds)) return '-'
  const sign = seconds < 0 ? '-' : ''
  const absolute = Math.abs(Math.round(seconds))
  const minutes = Math.floor(absolute / 60)
  const remain = absolute % 60
  return minutes > 0 ? `${sign}${minutes}m${remain.toString().padStart(2, '0')}s` : `${sign}${remain}s`
}

const formatOffset = (seconds: number | null | undefined) => {
  const value = formatSeconds(seconds)
  return value === '-' ? '-' : `T+${value}`
}

const formatNumber = (value: number | null | undefined, digits = 0) =>
  value === null || value === undefined || Number.isNaN(value) ? '-' : value.toFixed(digits)

const firstStop = (service: TrainServicePlan) => service.stops[0] ?? null
const lastStop = (service: TrainServicePlan) => service.stops[service.stops.length - 1] ?? null

const stationIds = computed(() => {
  if (props.plan?.stations?.length) return props.plan.stations.map((station) => station.id)
  const ids = new Set<string>()
  props.plan?.services?.forEach((service) => service.stops.forEach((stop) => ids.add(stop.stationId)))
  return [...ids]
})

const trainById = computed(() => {
  const entries = props.snapshot?.trains?.map((train) => [train.id, train] as const) ?? []
  return new Map(entries)
})

const commandByTrain = computed(() => {
  const map = new Map<string, DispatchCommandView[]>()
  props.dispatch.activeCommands.forEach((command) => {
    const key = command.regulatedTrainId || command.trainId
    const list = map.get(key) ?? []
    list.push(command)
    map.set(key, list)
  })
  return map
})

const profileByTrain = computed(() => {
  const entries = props.dispatch.trainProfiles.map((profile) => [profile.regulatedTrainId || profile.trainId, profile] as const)
  return new Map(entries)
})

const currentPeriod = computed<RunPlanPeriod | null>(() =>
  props.plan?.periods.find((period) => period.periodType === props.dispatch.runMode) ?? props.plan?.periods[0] ?? null
)

const orderedServices = computed(() =>
  [...(props.plan?.services ?? [])].sort((left, right) => {
    const leftDeparture = firstStop(left)?.departureOffsetSec ?? Number.MAX_SAFE_INTEGER
    const rightDeparture = firstStop(right)?.departureOffsetSec ?? Number.MAX_SAFE_INTEGER
    return leftDeparture - rightDeparture
  })
)

const averageAbsHeadwayDeviation = computed(() => {
  const deviations = props.dispatch.trainProfiles
    .map((profile) => Math.abs(profile.headwayErrorSeconds ?? profile.headwayDeviationSeconds ?? 0))
    .filter((value) => Number.isFinite(value))
  if (!deviations.length) return 0
  return deviations.reduce((sum, value) => sum + value, 0) / deviations.length
})

const abnormalTrainCount = computed(() =>
  props.dispatch.trainProfiles.filter((profile) =>
    profile.headwayState !== 'NORMAL' || Math.abs(profile.departureDelaySeconds ?? 0) > 0
  ).length
)

const confirmedCommandCount = computed(() =>
  props.dispatch.activeCommands.filter((command) => ['APPLIED', 'EFFECT_CONFIRMED', 'RELEASED'].includes(command.status)).length
)

const routeEvidenceCount = computed(() =>
  props.dispatch.routeReservations.filter((reservation) => ['ACCEPTED', 'LOCKED', 'ACTIVE', 'RELEASED'].includes(reservation.state)).length
)

const attentionRows = computed(() => {
  const disturbances = props.dispatch.openDisturbances.slice(0, 4).map((item) => ({
    id: item.id,
    title: item.disturbanceType,
    trainId: item.regulatedTrainId || item.trainId,
    detail: item.actualHeadwaySec
      ? `实际 ${formatSeconds(item.actualHeadwaySec)} / 目标 ${formatSeconds(item.targetHeadwaySec)}`
      : `偏差 ${formatSeconds(item.deviationValue)}`,
    state: item.status
  }))
  if (disturbances.length) return disturbances
  return props.dispatch.trainProfiles
    .filter((profile) => profile.headwayState !== 'NORMAL' || Math.abs(profile.headwayDeviationSeconds) > 0)
    .slice(0, 4)
    .map((profile) => ({
      id: profile.trainId,
      title: profile.headwayState,
      trainId: profile.regulatedTrainId || profile.trainId,
      detail: `剩余偏差 ${formatSeconds(profile.headwayErrorSeconds ?? profile.headwayDeviationSeconds)}`,
      state: profile.regulationAction
    }))
})

const effectRows = computed(() =>
  props.dispatch.trainProfiles.map((profile) => {
    const trainId = profile.regulatedTrainId || profile.trainId
    const commands = commandByTrain.value.get(trainId) ?? []
    const latestCommand = commands[0] ?? null
    return {
      trainId,
      frontTrainId: profile.frontTrainId,
      actual: profile.headwayActualSeconds,
      target: props.dispatch.targetHeadwaySeconds,
      remainingDeviation: profile.headwayErrorSeconds ?? profile.headwayDeviationSeconds,
      state: profile.headwayState,
      action: profile.regulationAction,
      reason: profile.regulationReason,
      command: latestCommand
    }
  })
)

const operationLine = computed(() => {
  const stations = props.plan?.stations ?? []
  const segments = props.plan?.segments ?? []
  return stations.map((station, index) => ({
    station,
    nextSegment: segments[index] ?? null
  }))
})

function stopFor(service: TrainServicePlan, stationId: string): PlannedStop | null {
  return service.stops.find((stop) => stop.stationId === stationId) ?? null
}

function stopCell(service: TrainServicePlan, stationId: string) {
  const stop = stopFor(service, stationId)
  if (!stop) return '-'
  const isTerminal = lastStop(service)?.stationId === stationId
  if (isTerminal || stop.arrivalOffsetSec === stop.departureOffsetSec) {
    return `到 ${formatOffset(stop.arrivalOffsetSec)}`
  }
  return `到 ${formatOffset(stop.arrivalOffsetSec)} / 发 ${formatOffset(stop.departureOffsetSec)}`
}

function trainNextStop(service: TrainServicePlan, train: TrainState | undefined) {
  if (!train) return firstStop(service)?.stationId ?? '-'
  const stations = props.plan?.stations ?? []
  const stationById = new Map(stations.map((station) => [station.id, station] as const))
  const next = service.stops.find((stop) => {
    const station = stationById.get(stop.stationId)
    return station ? station.positionMeters >= train.positionMeters - 5 : false
  })
  return next?.stationId ?? lastStop(service)?.stationId ?? '-'
}
</script>

<template>
  <div class="dispatch-page">
    <section class="kpi-strip">
      <article>
        <span>当前模式</span>
        <strong>{{ modeLabel(dispatch.runMode) }}</strong>
        <small>{{ currentPeriod ? `${currentPeriod.start}-${currentPeriod.end}` : '等待计划' }}</small>
      </article>
      <article>
        <span>目标间隔</span>
        <strong>{{ dispatch.targetHeadwaySeconds }}s</strong>
        <small>默认停站 {{ dispatch.defaultDwellSeconds }}s</small>
      </article>
      <article>
        <span>平均剩余偏差</span>
        <strong>{{ formatSeconds(averageAbsHeadwayDeviation) }}</strong>
        <small>{{ abnormalTrainCount }} 列需关注</small>
      </article>
      <article>
        <span>命令闭环</span>
        <strong>{{ confirmedCommandCount }}/{{ dispatch.activeCommands.length }}</strong>
        <small>{{ routeEvidenceCount }} 条进路反馈</small>
      </article>
    </section>

    <section class="workspace-grid">
      <section class="panel plan-panel">
        <header>
          <div>
            <h2>线路计划</h2>
            <p>{{ plan?.planId ?? dispatch.planId }} · {{ plan?.lineId ?? '-' }}</p>
          </div>
          <span class="pill">{{ plan?.services?.length ?? 0 }} 个车次</span>
        </header>

        <div v-if="operationLine.length" class="line-plan">
          <div v-for="item in operationLine" :key="item.station.id" class="line-node">
            <div class="station-dot"></div>
            <div>
              <strong>{{ item.station.id }}</strong>
              <span>{{ item.station.positionMeters.toFixed(0) }}m · 容量 {{ item.station.platformCapacity }}</span>
            </div>
            <small v-if="item.nextSegment">
              {{ item.nextSegment.id }} · {{ item.nextSegment.startMeters.toFixed(0) }}-{{ item.nextSegment.endMeters.toFixed(0) }}m ·
              {{ item.nextSegment.speedLimitMps.toFixed(1) }}m/s
            </small>
          </div>
        </div>
        <p v-else class="empty">暂无线路计划数据</p>
      </section>

      <section class="panel attention-panel">
        <header>
          <div>
            <h2>调度关注</h2>
            <p>按扰动和间隔偏差生成关注项</p>
          </div>
        </header>
        <div v-if="attentionRows.length" class="attention-list">
          <article v-for="item in attentionRows" :key="item.id">
            <span>{{ headwayStateLabel(item.title) }}</span>
            <strong>{{ item.trainId }}</strong>
            <small>{{ item.detail }} · {{ item.state }}</small>
          </article>
        </div>
        <p v-else class="empty">当前没有需要人工关注的调度偏差</p>
      </section>
    </section>

    <section class="panel timetable-panel">
      <header>
        <div>
          <h2>间隔时刻表</h2>
          <p>计划到发偏移与当前列车运行状态对照</p>
        </div>
      </header>
      <div v-if="orderedServices.length" class="table-wrap">
        <table>
          <thead>
            <tr>
              <th>车次</th>
              <th>列车</th>
              <th>方向</th>
              <th v-for="stationId in stationIds" :key="stationId">{{ stationId }}</th>
              <th>当前状态</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="service in orderedServices" :key="service.serviceId">
              <td>{{ service.serviceId }}</td>
              <td>{{ service.trainId }}</td>
              <td>{{ directionLabel(service.direction) }}</td>
              <td v-for="stationId in stationIds" :key="stationId">
                {{ stopCell(service, stationId) }}
              </td>
              <td>
                <template v-if="trainById.get(service.trainId)">
                  {{ trainStatusLabel(trainById.get(service.trainId)!.status) }}
                  · {{ trainById.get(service.trainId)!.positionMeters.toFixed(0) }}m
                  · 下一站 {{ trainNextStop(service, trainById.get(service.trainId)) }}
                </template>
                <template v-else>未上线</template>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <p v-else class="empty">暂无时刻表数据</p>
    </section>

    <section class="workspace-grid lower-grid">
      <section class="panel effect-panel">
        <header>
          <div>
            <h2>效果确认</h2>
            <p>用剩余间隔偏差和命令状态确认调度是否生效</p>
          </div>
        </header>
        <div v-if="effectRows.length" class="effect-list">
          <article v-for="item in effectRows" :key="item.trainId">
            <div class="effect-main">
              <strong>{{ item.trainId }}</strong>
              <span :data-state="item.state">{{ headwayStateLabel(item.state) }}</span>
            </div>
            <dl>
              <div>
                <dt>前车</dt>
                <dd>{{ item.frontTrainId ?? '-' }}</dd>
              </div>
              <div>
                <dt>实际/目标</dt>
                <dd>{{ formatSeconds(item.actual) }} / {{ formatSeconds(item.target) }}</dd>
              </div>
              <div>
                <dt>剩余偏差</dt>
                <dd>{{ formatSeconds(item.remainingDeviation) }}</dd>
              </div>
              <div>
                <dt>策略</dt>
                <dd>{{ regulationLabel(item.action) }}</dd>
              </div>
            </dl>
            <p>
              {{ item.reason }}
              <span v-if="item.command">
                · {{ commandLabel(item.command.commandType) }} / {{ commandStatusLabel(item.command.status) }}
              </span>
            </p>
          </article>
        </div>
        <p v-else class="empty">暂无可评估的调度效果</p>
      </section>

      <section class="panel signal-panel">
        <header>
          <div>
            <h2>信号轨道反馈</h2>
            <p>只展示调度相关的进路与预约状态</p>
          </div>
        </header>
        <div v-if="dispatch.routeReservations.length || dispatch.routeDecisions.length" class="signal-list">
          <article v-for="reservation in dispatch.routeReservations.slice(0, 5)" :key="reservation.reservationId">
            <strong>{{ reservation.routeId }}</strong>
            <span>{{ reservation.trainId }} · {{ reservation.state }}</span>
            <small v-if="reservation.rejectReason">{{ reservation.rejectReason }}</small>
            <small v-else>命令 {{ reservation.commandId }} · 重试 {{ reservation.retryCount }}</small>
          </article>
          <article v-for="decision in dispatch.routeDecisions.slice(0, 3)" :key="decision.decisionId">
            <strong>{{ decision.selectedRouteId }}</strong>
            <span>{{ decision.selectedTrainId }} · {{ decision.status }}</span>
            <small>{{ decision.reason }}</small>
          </article>
        </div>
        <p v-else class="empty">暂无进路申请或联锁反馈</p>
      </section>
    </section>

    <section class="workspace-grid lower-grid">
      <DisturbanceList :disturbances="dispatch.openDisturbances" />
      <CommandTimeline :commands="dispatch.activeCommands" />
    </section>

    <section v-if="snapshot" class="panel trains-panel">
      <header>
        <div>
          <h2>列车实时状态</h2>
          <p>车辆位置、速度、MA 与调度约束观察值</p>
        </div>
      </header>
      <div class="table-wrap">
        <table>
          <thead>
            <tr>
              <th>车次</th>
              <th>位置(m)</th>
              <th>速度(m/s)</th>
              <th>状态</th>
              <th>站点</th>
              <th>停站(s)</th>
              <th>限速(m/s)</th>
              <th>MA距离(m)</th>
              <th>车辆约束</th>
              <th>间隔偏差</th>
              <th>满载率</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="train in snapshot.trains" :key="train.id">
              <td>{{ train.id }}</td>
              <td>{{ train.positionMeters.toFixed(1) }}</td>
              <td>{{ train.speedMetersPerSecond.toFixed(1) }}</td>
              <td>{{ trainStatusLabel(train.status) }}</td>
              <td>{{ train.currentStationId || '-' }}</td>
              <td>{{ train.dwellElapsedSeconds ?? 0 }}</td>
              <td>{{ train.speedLimitMetersPerSecond.toFixed(1) }}</td>
              <td>{{ train.movementAuthorityDistanceMeters.toFixed(1) }}</td>
              <td>{{ dynamicsReasonLabel(train.dynamicsConstraintReason || '-') }}</td>
              <td>{{ formatSeconds(profileByTrain.get(train.id)?.headwayErrorSeconds ?? profileByTrain.get(train.id)?.headwayDeviationSeconds) }}</td>
              <td>{{ formatNumber((train.loadRate ?? 0) * 100) }}%</td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>
    <section v-else class="panel empty-state">
      暂无列车数据。请使用页面顶部的启动或步进控制。
    </section>
  </div>
</template>

<style scoped>
.dispatch-page {
  display: grid;
  gap: 16px;
  padding: 16px 20px 20px;
  max-width: 1440px;
  margin: 0 auto;
  color: #172033;
}

.kpi-strip {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 12px;
}

.kpi-strip article,
.panel {
  background: #fff;
  border: 1px solid #d8e1ea;
  border-radius: 8px;
}

.kpi-strip article {
  display: grid;
  gap: 6px;
  padding: 14px;
}

.kpi-strip span,
.kpi-strip small,
header p,
.line-node span,
.line-node small,
.effect-list dt,
.effect-list p,
.signal-list small,
.attention-list small {
  color: #65758b;
}

.kpi-strip strong {
  font-size: 24px;
  line-height: 1;
}

.workspace-grid {
  display: grid;
  grid-template-columns: minmax(0, 1.35fr) minmax(320px, 0.65fr);
  gap: 16px;
}

.lower-grid {
  grid-template-columns: repeat(2, minmax(0, 1fr));
}

.panel {
  padding: 16px;
  min-width: 0;
}

header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 14px;
}

h2 {
  margin: 0;
  font-size: 16px;
}

header p {
  margin: 4px 0 0;
  font-size: 12px;
}

.pill {
  flex: 0 0 auto;
  border-radius: 999px;
  padding: 4px 10px;
  background: #e8f0ff;
  color: #2452a6;
  font-size: 12px;
  font-weight: 700;
}

.line-plan {
  display: grid;
  gap: 0;
}

.line-node {
  position: relative;
  display: grid;
  grid-template-columns: 18px minmax(120px, 0.45fr) minmax(180px, 1fr);
  gap: 10px;
  min-height: 58px;
  padding-bottom: 10px;
  font-size: 13px;
}

.line-node::after {
  content: '';
  position: absolute;
  left: 6px;
  top: 18px;
  bottom: 0;
  width: 2px;
  background: #cad5e2;
}

.line-node:last-child::after {
  display: none;
}

.station-dot {
  position: relative;
  z-index: 1;
  width: 14px;
  height: 14px;
  margin-top: 2px;
  border-radius: 50%;
  background: #1f7a5f;
  border: 3px solid #dff5eb;
}

.line-node div:nth-child(2) {
  display: grid;
  gap: 3px;
}

.attention-list,
.effect-list,
.signal-list {
  display: grid;
  gap: 10px;
}

.attention-list article,
.effect-list article,
.signal-list article {
  border: 1px solid #e5edf5;
  border-radius: 8px;
  padding: 10px;
  background: #fbfcfe;
}

.attention-list article {
  display: grid;
  grid-template-columns: 1fr auto;
  gap: 4px 10px;
}

.attention-list span {
  font-size: 12px;
  color: #9a4f00;
  font-weight: 700;
}

.attention-list strong {
  text-align: right;
}

.attention-list small {
  grid-column: 1 / -1;
}

.table-wrap {
  overflow-x: auto;
}

table {
  width: 100%;
  min-width: 920px;
  border-collapse: collapse;
  font-size: 12px;
}

th,
td {
  border-bottom: 1px solid #edf2f7;
  padding: 9px 8px;
  text-align: left;
  vertical-align: top;
  white-space: nowrap;
}

th {
  color: #526173;
  font-weight: 700;
  background: #f7f9fc;
}

.effect-main {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.effect-main span {
  border-radius: 999px;
  padding: 2px 8px;
  background: #e8f0ff;
  color: #2452a6;
  font-size: 11px;
}

.effect-main span[data-state='TOO_SHORT'],
.effect-main span[data-state='SCHEDULE_LATE'] {
  background: #fff0d8;
  color: #9a4f00;
}

.effect-main span[data-state='TOO_LONG'] {
  background: #ecfdf5;
  color: #08704f;
}

dl {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 8px;
  margin: 10px 0 0;
}

dt,
dd {
  margin: 0;
}

dd {
  margin-top: 2px;
  font-weight: 700;
}

.effect-list p {
  margin: 8px 0 0;
  line-height: 1.45;
  font-size: 12px;
}

.signal-list article {
  display: grid;
  grid-template-columns: 0.8fr 1fr;
  gap: 4px 8px;
}

.signal-list small {
  grid-column: 1 / -1;
}

.empty,
.empty-state {
  color: #7a8a9d;
  font-size: 13px;
}

.empty {
  margin: 0;
}

@media (max-width: 1100px) {
  .kpi-strip,
  .workspace-grid,
  .lower-grid {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 720px) {
  .dispatch-page {
    padding: 12px;
  }

  .line-node {
    grid-template-columns: 18px 1fr;
  }

  .line-node small {
    grid-column: 2;
  }

  dl {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
</style>
