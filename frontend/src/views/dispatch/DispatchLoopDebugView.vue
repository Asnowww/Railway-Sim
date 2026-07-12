<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { dispatchApi } from '../../api/dispatch'
import { useSimulation } from '../../composables/useSimulation'
import type { DispatchDisturbance, DispatchRouteInfo } from '../../types/dispatch'
import type { TrainState } from '../../types/simulation'

defineEmits<{
  back: []
}>()

const {
  plan,
  snapshot,
  dispatch,
  status,
  tick,
  errorMessage,
  backendReady,
  autoRunning,
  runSimulation,
  toggleAutoRun,
} = useSimulation()

const trains = computed(() => snapshot.value?.trains ?? [])
const authorities = computed(() => snapshot.value?.authorities ?? [])
const authorityByTrain = computed(() => new Map(authorities.value.map((authority) => [authority.trainId, authority])))
const routes = computed(() => snapshot.value?.routeStates ?? [])
const switches = computed(() => snapshot.value?.switchStates ?? [])
const signals = computed(() => snapshot.value?.signalStates ?? [])
const routeDecisions = computed(() => dispatch.value.routeDecisions ?? [])
const routeReservations = computed(() => dispatch.value.routeReservations ?? [])

const activeTrainCount = computed(() => trains.value.length)
const dwellingTrainCount = computed(() => trains.value.filter((train) => train.status === 'DWELLING').length)
const activeCommandCount = computed(() => dispatch.value.activeCommands.length)
const openDisturbanceCount = computed(() => dispatch.value.openDisturbances.length)
const demoSpeedLimitMps = ref(6)
const demoShortHeadwaySec = ref(180)
const demoLongHeadwaySec = ref(540)
const comparisonBaseline = ref<ComparisonBaseline | null>(null)
const dispatchRouteList = ref<DispatchRouteInfo[]>([])
const selectedRouteId = ref('')
const selectedRouteTrainId = ref('')
const routeOperationMessage = ref('')
const routeRequestPending = ref(false)
const headwayShrinkRatio = 0.7
const headwayExpandRatio = 1.5

const orderedTrains = computed(() =>
  [...trains.value].sort((firstTrain, secondTrain) => firstTrain.positionMeters - secondTrain.positionMeters)
)
const rearTrain = computed(() => orderedTrains.value[0] ?? null)
const frontTrain = computed(() => orderedTrains.value[1] ?? null)
const primaryHeadwayProfile = computed(() => {
  if (rearTrain.value) {
    const rearProfile = dispatch.value.trainProfiles.find((profile) => profile.trainId === rearTrain.value?.id)
    if (rearProfile) return rearProfile
  }
  return dispatch.value.trainProfiles.find((profile) => profile.frontTrainId) ?? dispatch.value.trainProfiles[0] ?? null
})
const headwayTargetSec = computed(() => Math.max(1, dispatch.value.targetHeadwaySeconds || 1))
const headwayTooShortLimitSec = computed(() => headwayTargetSec.value * headwayShrinkRatio)
const headwayTooLongLimitSec = computed(() => headwayTargetSec.value * headwayExpandRatio)
const headwayActualSec = computed(() => primaryHeadwayProfile.value?.headwayActualSeconds ?? null)
const headwayViolation = computed(() => {
  const actual = headwayActualSec.value
  if (actual === null) {
    return {
      state: 'WAITING',
      label: '等待离站数据',
      value: null,
      hint: '至少需要两列车都有离站记录'
    }
  }
  if (actual < headwayTooShortLimitSec.value) {
    return {
      state: 'TOO_SHORT',
      label: '过短',
      value: headwayTooShortLimitSec.value - actual,
      hint: '调度对象是后车：延长停站或压低速度'
    }
  }
  if (actual > headwayTooLongLimitSec.value) {
    return {
      state: 'TOO_LONG',
      label: '过长',
      value: actual - headwayTooLongLimitSec.value,
      hint: '调度对象是后车：缩短停站或适度追赶'
    }
  }
  return {
    state: 'ON_TARGET',
    label: '正常',
    value: 0,
    hint: '实际间隔在允许容差内'
  }
})
const headwayControlledTrainId = computed(() => primaryHeadwayProfile.value?.trainId ?? rearTrain.value?.id ?? '-')
const headwayFrontTrainId = computed(() => primaryHeadwayProfile.value?.frontTrainId ?? frontTrain.value?.id ?? '-')
const lineEndMeters = computed(() => {
  const ends = snapshot.value?.trackSegments.map((segment) => segment.endMeters) ?? []
  return ends.length > 0 ? Math.max(...ends) : null
})
const currentGapMeters = computed(() => spatialGapMeters(rearTrain.value, frontTrain.value))
const comparisonWarnings = computed(() => {
  const baseline = comparisonBaseline.value
  if (!baseline) return []
  const targetTrain = trains.value.find((train) => train.id === baseline.trainId)
  const targetAuthority = authorityByTrain.value.get(baseline.trainId)
  const targetProfile = dispatch.value.trainProfiles.find((profile) => profile.trainId === baseline.trainId)
  const warnings: string[] = []
  const isHeadwayDemo = baseline.commandType === 'HEADWAY_TOO_SHORT' || baseline.commandType === 'HEADWAY_TOO_LONG'

  if (isHeadwayDemo && baseline.headwayActualSeconds === null && !targetProfile?.headwayActualSeconds) {
    warnings.push('当前没有实际发车间隔数据，不能用“实际发车间隔”确认闭环，只能先看速度、MA、停站和空间间隔。')
  }
  if (targetAuthority?.reason?.includes('站台停靠') || targetAuthority?.reason?.includes('STATION')) {
    warnings.push('目标车当前受信号站停/站台 MA 约束，速度为 0 不一定全部来自调度指令。')
  }
  if (targetTrain && targetTrain.speedMetersPerSecond <= 0.1 && targetTrain.status !== 'DWELLING') {
    warnings.push('目标车速度为 0 但车辆状态不是停站，优先查看 MA 距离和车辆约束原因。')
  }
  if (targetTrain && nearTerminal(targetTrain)) {
    warnings.push('目标车已接近线路终点，终点安全限制会覆盖时间间隔调度效果。')
  }
  return warnings
})
const routeReservationByDecision = computed(() =>
  new Map(dispatch.value.routeReservations.map((reservation) => [reservation.decisionId, reservation]))
)
const comparisonRows = computed(() => {
  const baseline = comparisonBaseline.value
  if (!baseline) return []
  const targetTrain = trains.value.find((train) => train.id === baseline.trainId)
  const targetAuthority = authorityByTrain.value.get(baseline.trainId)
  const targetProfile = dispatch.value.trainProfiles.find((profile) => profile.trainId === baseline.trainId)
  return [
    {
      label: '参考前车',
      before: baseline.frontTrainId ?? '-',
      after: targetProfile?.frontTrainId ?? '-',
      delta: '看当前车相对前车'
    },
    {
      label: '间隔状态',
      before: headwayStateLabel(baseline.headwayState),
      after: headwayStateLabel(targetProfile?.headwayState ?? 'WAITING_DEPARTURE_DATA'),
      delta: headwayActionLabel(targetProfile?.headwayAction ?? 'OBSERVE')
    },
    {
      label: '车辆约束',
      before: dynamicsReasonLabel(baseline.constraintReason || '-'),
      after: dynamicsReasonLabel(targetTrain?.dynamicsConstraintReason || '-'),
      delta: targetAuthority?.reason || '-'
    },
    {
      label: '空间间隔',
      before: formatMeters(baseline.gapMeters),
      after: formatMeters(currentGapMeters.value),
      delta: formatDelta(currentGapMeters.value, baseline.gapMeters, 'm')
    },
    {
      label: '实际发车间隔',
      before: formatSeconds(baseline.headwayActualSeconds),
      after: formatSeconds(targetProfile?.headwayActualSeconds ?? null),
      delta: formatDelta(targetProfile?.headwayActualSeconds ?? null, baseline.headwayActualSeconds, 's')
    },
    {
      label: '演示目标间隔',
      before: formatSeconds(baseline.targetHeadwaySeconds),
      after: formatSeconds(baseline.commandTargetHeadwaySeconds ?? dispatch.value.targetHeadwaySeconds),
      delta: formatDelta(baseline.commandTargetHeadwaySeconds ?? dispatch.value.targetHeadwaySeconds, baseline.targetHeadwaySeconds, 's')
    },
    {
      label: '目标车速度',
      before: formatMps(baseline.speedMps),
      after: formatMps(targetTrain?.speedMetersPerSecond ?? null),
      delta: formatDelta(targetTrain?.speedMetersPerSecond ?? null, baseline.speedMps, 'm/s')
    },
    {
      label: '目标车限速',
      before: formatMps(baseline.speedLimitMps),
      after: formatMps(targetTrain?.speedLimitMetersPerSecond ?? null),
      delta: formatDelta(targetTrain?.speedLimitMetersPerSecond ?? null, baseline.speedLimitMps, 'm/s')
    },
    {
      label: '目标车MA距离',
      before: formatMeters(baseline.maDistanceMeters),
      after: formatMeters(targetAuthority ? targetAuthority.authorityEndMeters - (targetTrain?.positionMeters ?? 0) : null),
      delta: formatDelta(
        targetAuthority && targetTrain ? targetAuthority.authorityEndMeters - targetTrain.positionMeters : null,
        baseline.maDistanceMeters,
        'm'
      )
    },
    {
      label: '目标车到终点',
      before: formatMeters(baseline.terminalRemainingMeters),
      after: formatMeters(targetTrain ? remainingToTerminal(targetTrain) : null),
      delta: formatDelta(targetTrain ? remainingToTerminal(targetTrain) : null, baseline.terminalRemainingMeters, 'm')
    }
  ]
})

interface ComparisonBaseline {
  commandType: string
  trainId: string
  capturedTick: number
  frontTrainId: string | null
  headwayState: string
  headwayAction: string
  targetHeadwaySeconds: number
  commandTargetHeadwaySeconds: number | null
  headwayActualSeconds: number | null
  gapMeters: number | null
  speedMps: number | null
  speedLimitMps: number | null
  maDistanceMeters: number | null
  terminalRemainingMeters: number | null
  constraintReason: string
}

const commandLabel = (type: string) => {
  const labels: Record<string, string> = {
    SHORTEN_DWELL: '缩短停站/尽快发车',
    EXTEND_DWELL: '延长停站',
    HEADWAY_ADJUST: '调整行车间隔',
    SPEED_BIAS: '速度偏置',
    SPEED_LIMIT: '临时限速',
    TEMP_SPEED_LIMIT: '临时限速',
    SPEED_FACTOR: '速度系数',
    LIMIT_FACTOR: '限速系数',
    HOLD: '扣车',
    HOLD_TRAIN: '扣车',
    DEPART: '发车',
    REQUEST_ROUTE: '申请进路',
    REROUTE: '重排进路',
    REQUEST_ROUTE: '申请进路',
    HEADWAY_TOO_SHORT: '拉开后车间隔',
    HEADWAY_TOO_LONG: '催发后车追赶',
  }
  return labels[type] ?? type
}

const commandStatusLabel = (statusText: string) => {
  const labels: Record<string, string> = {
    PENDING: '待下发',
    SENT: '已下发',
    APPLIED: '已观测到执行',
    EFFECT_CONFIRMED: '闭环已完成',
    TIMEOUT: '执行超时',
    SKIPPED: '已跳过',
    CANCELLED: '已取消',
    EXPIRED: '已结束',
    RELEASED: '已释放',
    COMPLETED: '旧完成态',
  }
  return labels[statusText] ?? statusText
}

const commandStatusHint = (statusText: string) => {
  const hints: Record<string, string> = {
    PENDING: '调度已生成指令，等待进入信号/车辆执行链路。',
    SENT: '指令已发出，正在等待信号/车辆状态反馈。',
    APPLIED: '已经从 MA、速度、停站释放等状态观察到指令开始起作用。',
    EFFECT_CONFIRMED: '调度确认效果已闭环，指令不应继续施加约束。',
    TIMEOUT: '观察窗口内没有确认效果，需要检查信号、车辆或线路约束。',
    SKIPPED: '指令没有进入执行链路。',
    CANCELLED: '指令已取消。',
    EXPIRED: '指令已结束。',
  }
  return hints[statusText] ?? '调度指令状态持续记录。'
}

const trainStatusLabel = (statusText: string) => {
  const labels: Record<string, string> = {
    RUNNING: '运行',
    DWELLING: '停站',
    DEPARTING_STATION: '离站抑制/出站',
    EMERGENCY_BRAKE: '紧急制动',
    DEGRADED: '降级',
    FAULT: '故障',
  }
  return labels[statusText] ?? statusText
}

const dynamicsReasonLabel = (reason: string) => {
  const labels: Record<string, string> = {
    STATION_STOP_WINDOW: '站停窗口',
    STATION_RELEASE_WINDOW: '离站释放窗口',
    STATION_APPROACH: '进站制动',
    MA_DISTANCE_LIMIT: '移动授权距离限制',
    MOVEMENT_AUTHORITY_EXHAUSTED: '移动授权已用尽',
    SPEED_LIMIT_EXCEEDED: '超过限速',
    SPEED_MARGIN_AVAILABLE: '可牵引加速',
    NEAR_TARGET_SPEED: '接近目标速度',
    TARGET_SPEED_REACHED: '达到目标速度',
    TRACTION_UNAVAILABLE: '牵引不可用',
    OVERCURRENT: '过流保护',
    NONE: '无约束',
  }
  return labels[reason] ?? reason
}

const signalAspectLabel = (aspect: string) => {
  const labels: Record<string, string> = {
    GREEN: '绿灯',
    YELLOW: '黄灯',
    RED: '红灯',
  }
  return labels[aspect] ?? aspect
}

const routeStatusLabel = (routeStatus: string) => {
  const labels: Record<string, string> = {
    AVAILABLE: '可用',
    VALIDATING: '校验中',
    SETTING_SWITCHES: '设置道岔',
    LOCKED: '已锁闭',
    OCCUPIED: '列车占用',
    RELEASING: '释放中',
    RELEASED: '已释放',
    CONFLICTED: '冲突',
    REJECTED: '已拒绝',
    FAILED: '执行失败',
    CANCELLED: '已取消',
    EXPIRED_BY_RESET: '重置终止',
  }
  return labels[routeStatus] ?? routeStatus
}

const routeReservationStateLabel = (state: string) => {
  const labels: Record<string, string> = {
    REQUESTED: '已申请',
    ACCEPTED: '联锁接受',
    RELEASED: '已释放',
    EXPIRED: '已过期',
    REJECTED: '联锁拒绝',
    CANCELLED: '已取消',
  }
  return labels[state] ?? state
}

const routeDecisionStatusLabel = (statusText: string) => {
  const labels: Record<string, string> = {
    REQUESTED: '已请求',
    ACCEPTED: '已接受',
    REJECTED: '已拒绝',
    CANCELLED: '已取消',
  }
  return labels[statusText] ?? statusText
}

const headwayStateLabel = (state: string) => {
  const labels: Record<string, string> = {
    LEADING_TRAIN: '头车',
    WAITING_DEPARTURE_DATA: '等待发车数据',
    TOO_SHORT: '间隔过短',
    TOO_LONG: '间隔过长',
    ON_TARGET: '间隔正常',
  }
  return labels[state] ?? state
}

const headwayActionLabel = (action: string) => {
  const labels: Record<string, string> = {
    NONE: '无需干预',
    OBSERVE: '继续观测',
    SLOW_REAR_TRAIN: '后车减速/延长停站',
    CATCH_UP_REAR_TRAIN: '后车追赶/缩短停站',
  }
  return labels[action] ?? action
}

const formatNumber = (value: number | null | undefined, digits = 1) => {
  if (value === null || value === undefined || Number.isNaN(value)) return '-'
  return value.toFixed(digits)
}

const disturbanceMetricText = (disturbance: DispatchDisturbance) => {
  if (disturbance.disturbanceType !== 'HEADWAY_VIOLATION') {
    return `偏差 ${formatNumber(disturbance.deviationValue, 0)}s`
  }
  const direction = disturbance.headwayDirection === 'TOO_SHORT' ? '过短' : '过长'
  return `${direction} · 实际 ${formatNumber(disturbance.actualHeadwaySec, 0)}s / 目标 ${formatNumber(disturbance.targetHeadwaySec, 0)}s · 容差 ${formatNumber(disturbance.toleranceSec, 0)}s · 超限 ${formatNumber(disturbance.violationSec ?? disturbance.deviationValue, 0)}s`
}

const formatPercent = (value: number | null | undefined) => {
  if (value === null || value === undefined || Number.isNaN(value)) return '-'
  return `${Math.round(value * 100)}%`
}

async function loadDispatchRoutes() {
  try {
    dispatchRouteList.value = await dispatchApi.routeList()
    selectedRouteId.value ||= dispatchRouteList.value[0]?.routeId ?? ''
  } catch (error) {
    routeOperationMessage.value = error instanceof Error ? `进路列表加载失败：${error.message}` : '进路列表加载失败'
  }
}

async function requestSelectedRoute() {
  const routeId = selectedRouteId.value
  const trainId = selectedRouteTrainId.value || trains.value[0]?.id || ''
  if (!routeId || !trainId || routeRequestPending.value) return

  routeRequestPending.value = true
  try {
    const command = await dispatchApi.submitCommand({
      trainId,
      commandType: 'REQUEST_ROUTE',
      routeId
    })
    const decisionId = textPayloadValue(command.payload?.decisionId)
    const reservationId = textPayloadValue(command.payload?.reservationId)
    routeOperationMessage.value = `进路申请已入队：指令 ${command.id} / 决策 ${decisionId} / 预留 ${reservationId}`

    await runSimulation('tick')
    await loadDispatchRoutes()

    const currentCommand = dispatch.value.activeCommands.find((item) => item.id === command.id)
    const decision = dispatch.value.routeDecisions.find((item) => item.routeCommandId === command.id)
    const reservation = decision ? routeReservationByDecision.value.get(decision.decisionId) : undefined
    const interlocking = routes.value.find((route) => route.routeId === routeId)
    routeOperationMessage.value = [
      `指令 ${command.id}（${commandStatusLabel(currentCommand?.status ?? command.status)}）`,
      `决策 ${decision?.decisionId ?? decisionId}（${decision?.status ?? '等待处理'}）`,
      `预留 ${reservation?.reservationId ?? reservationId}（${reservation?.state ?? '等待处理'}）`,
      `联锁 ${routeStatusLabel(interlocking?.status ?? 'AVAILABLE')}`
    ].join(' / ')
  } catch (error) {
    routeOperationMessage.value = error instanceof Error ? `进路申请失败：${error.message}` : '进路申请失败'
  } finally {
    routeRequestPending.value = false
  }
}

function textPayloadValue(value: unknown) {
  return typeof value === 'string' && value.length > 0 ? value : '-'
}

onMounted(loadDispatchRoutes)

async function submitLoopDemoCommand() {
  const targetTrain = rearTrain.value ?? trains.value[0]
  if (!targetTrain) return
  captureBaseline('SPEED_LIMIT', targetTrain)
  await dispatchApi.submitCommand({
    trainId: targetTrain.id,
    commandType: 'SPEED_LIMIT',
    detail: String(normalizedSpeedLimit())
  })
  await runSimulation('tick')
}

async function cancelCommand(commandId: string) {
  await dispatchApi.cancelCommand(commandId)
  await runSimulation('tick')
}

async function submitHeadwayDemo(kind: 'tooShort' | 'tooLong') {
  const targetTrain = rearTrain.value ?? trains.value[0]
  if (!targetTrain) return
  const targetHeadwaySec = kind === 'tooShort'
    ? normalizedHeadway(demoLongHeadwaySec.value)
    : normalizedHeadway(demoShortHeadwaySec.value)
  captureBaseline(kind === 'tooShort' ? 'HEADWAY_TOO_SHORT' : 'HEADWAY_TOO_LONG', targetTrain, targetHeadwaySec)
  await dispatchApi.submitCommand({
    trainId: targetTrain.id,
    commandType: 'HEADWAY_ADJUST',
    targetHeadwaySec
  })
  await runSimulation('tick')
}

function normalizedSpeedLimit() {
  if (!Number.isFinite(demoSpeedLimitMps.value)) return 6
  return Math.min(Math.max(Math.round(demoSpeedLimitMps.value * 10) / 10, 1), 22.2)
}

function normalizedHeadway(value: number) {
  if (!Number.isFinite(value)) return dispatch.value.targetHeadwaySeconds
  return Math.min(Math.max(Math.round(value), 30), 900)
}

function canCancelCommand(command: { commandType: string; status: string; reason: string }) {
  return command.reason === 'MANUAL'
    && ['SPEED_LIMIT', 'TEMP_SPEED_LIMIT'].includes(command.commandType)
    && ['PENDING', 'SENT', 'APPLIED'].includes(command.status)
}

function stationObservation(trainId: string, currentStationId: string | null | undefined) {
  const train = trains.value.find((item) => item.id === trainId)
  if (train && nearTerminal(train)) return currentStationId ? `${currentStationId} / 终点附近` : '终点附近'
  if (currentStationId) return currentStationId
  const authority = authorityByTrain.value.get(trainId)
  if (authority?.reason?.includes('站台停靠')) return '信号站停'
  return '-'
}

function captureBaseline(commandType: string, targetTrain: TrainState, commandTargetHeadwaySeconds: number | null = null) {
  const targetAuthority = authorityByTrain.value.get(targetTrain.id)
  const targetProfile = dispatch.value.trainProfiles.find((profile) => profile.trainId === targetTrain.id)
  comparisonBaseline.value = {
    commandType,
    trainId: targetTrain.id,
    capturedTick: tick.value,
    frontTrainId: targetProfile?.frontTrainId ?? null,
    headwayState: targetProfile?.headwayState ?? 'WAITING_DEPARTURE_DATA',
    headwayAction: targetProfile?.headwayAction ?? 'OBSERVE',
    targetHeadwaySeconds: dispatch.value.targetHeadwaySeconds,
    commandTargetHeadwaySeconds,
    headwayActualSeconds: targetProfile?.headwayActualSeconds ?? null,
    gapMeters: currentGapMeters.value,
    speedMps: targetTrain.speedMetersPerSecond,
    speedLimitMps: targetTrain.speedLimitMetersPerSecond,
    maDistanceMeters: targetAuthority ? targetAuthority.authorityEndMeters - targetTrain.positionMeters : null,
    terminalRemainingMeters: remainingToTerminal(targetTrain),
    constraintReason: targetTrain.dynamicsConstraintReason || '-'
  }
}

function nearTerminal(train: TrainState) {
  return lineEndMeters.value !== null && lineEndMeters.value - train.positionMeters <= 20
}

function remainingToTerminal(train: TrainState) {
  return lineEndMeters.value === null ? null : Math.max(0, lineEndMeters.value - train.positionMeters)
}

function spatialGapMeters(rear: TrainState | null, front: TrainState | null) {
  if (!rear || !front) return null
  return Math.max(0, front.positionMeters - front.lengthMeters - rear.positionMeters)
}

function formatMps(value: number | null | undefined) {
  return value === null || value === undefined || Number.isNaN(value) ? '-' : `${value.toFixed(1)} m/s`
}

function formatMeters(value: number | null | undefined) {
  return value === null || value === undefined || Number.isNaN(value) ? '-' : `${value.toFixed(1)} m`
}

function formatSeconds(value: number | null | undefined) {
  return value === null || value === undefined || Number.isNaN(value) ? '-' : `${value.toFixed(0)} s`
}

function formatDelta(current: number | null | undefined, baseline: number | null | undefined, unit: string) {
  if (current === null || current === undefined || baseline === null || baseline === undefined) return '-'
  const delta = current - baseline
  const sign = delta > 0 ? '+' : ''
  return `${sign}${delta.toFixed(unit === 's' ? 0 : 1)} ${unit}`
}
</script>

<template>
  <main class="debug-shell">
    <header class="debug-header">
      <section>
        <p class="eyebrow">Dispatch Closed Loop</p>
        <h1>调度闭环调试台</h1>
        <p>实时查看调度指令、扰动、列车状态、MA、信号、进路和道岔。</p>
      </section>
      <section class="debug-actions" aria-label="仿真控制">
        <button type="button" class="ghost-button" @click="$emit('back')">返回大屏</button>
        <button type="button" @click="runSimulation('start')">启动</button>
        <button type="button" @click="runSimulation('pause')">暂停</button>
        <button type="button" @click="runSimulation('reset')">重置</button>
        <button type="button" @click="runSimulation('tick')">步进</button>
        <button type="button" :class="{ active: autoRunning }" @click="toggleAutoRun">
          {{ autoRunning ? '停止自动步进' : '自动步进' }}
        </button>
      </section>
    </header>

    <section v-if="errorMessage" class="debug-banner error">{{ errorMessage }}</section>
    <section v-else-if="!backendReady" class="debug-banner warn">正在连接后端快照接口和 WebSocket。</section>

    <section class="metric-grid">
      <article>
        <span>仿真状态</span>
        <strong>{{ status }}</strong>
        <small>tick {{ tick }}</small>
      </article>
      <article>
        <span>计划</span>
        <strong>{{ dispatch.planId || plan?.planId || '-' }}</strong>
        <small>{{ dispatch.runMode }} / {{ dispatch.targetHeadwaySeconds }}s 间隔</small>
      </article>
      <article>
        <span>上线列车</span>
        <strong>{{ activeTrainCount }}</strong>
        <small>{{ dwellingTrainCount }} 列停站</small>
      </article>
      <article>
        <span>调度干预</span>
        <strong>{{ dispatch.interventionActive ? '进行中' : '空闲' }}</strong>
        <small>{{ activeCommandCount }} 条指令 / {{ openDisturbanceCount }} 个扰动</small>
      </article>
    </section>

    <section class="headway-focus" :data-state="headwayViolation.state">
      <div class="panel-title">
        <h2>运行间隔关键指标</h2>
        <span>后车相对前车 / 超限时间</span>
      </div>
      <div class="headway-kpi-grid">
        <article class="headway-kpi controlled">
          <span>调度对象</span>
          <strong>{{ headwayControlledTrainId }}</strong>
          <small>参考前车 {{ headwayFrontTrainId }}</small>
        </article>
        <article class="headway-kpi">
          <span>目标间隔</span>
          <strong>{{ formatSeconds(headwayTargetSec) }}</strong>
          <small>计划值</small>
        </article>
        <article class="headway-kpi">
          <span>实际间隔</span>
          <strong>{{ formatSeconds(headwayActualSec) }}</strong>
          <small>{{ primaryHeadwayProfile ? headwayStateLabel(primaryHeadwayProfile.headwayState) : '等待观测' }}</small>
        </article>
        <article class="headway-kpi">
          <span>允许范围</span>
          <strong>{{ formatSeconds(headwayTooShortLimitSec) }} ~ {{ formatSeconds(headwayTooLongLimitSec) }}</strong>
          <small>低于下限需拉开，高于上限需追赶</small>
        </article>
        <article class="headway-kpi highlight">
          <span>超限时间</span>
          <strong>{{ headwayViolation.value === null ? '-' : formatSeconds(headwayViolation.value) }}</strong>
          <small>{{ headwayViolation.label }}</small>
        </article>
      </div>
      <p class="headway-focus-hint">{{ headwayViolation.hint }}</p>
    </section>

    <section class="debug-grid">
      <section class="debug-panel command-panel">
        <div class="panel-title">
          <h2>调度指令闭环</h2>
          <span>生成 -> 下发 -> 观测执行 -> 效果确认</span>
        </div>
        <div class="demo-control-bar">
          <label>
            <span>临时限速 m/s</span>
            <input v-model.number="demoSpeedLimitMps" type="number" min="1" max="22.2" step="0.5" />
          </label>
          <button type="button" class="demo-button" :disabled="trains.length === 0" @click="submitLoopDemoCommand">
            发送限速闭环
          </button>
          <label>
            <span>拉开后车目标间隔 s</span>
            <input v-model.number="demoLongHeadwaySec" type="number" min="30" max="900" step="10" />
          </label>
          <button type="button" :disabled="trains.length === 0" @click="submitHeadwayDemo('tooShort')">
            拉开后车间隔
          </button>
          <label>
            <span>追赶后车目标间隔 s</span>
            <input v-model.number="demoShortHeadwaySec" type="number" min="30" max="900" step="10" />
          </label>
          <button type="button" :disabled="trains.length === 0" @click="submitHeadwayDemo('tooLong')">
            催发后车追赶
          </button>
        </div>
        <p v-if="dispatch.activeCommands.length === 0" class="empty">暂无调度指令。可以启动仿真并等待停站/间隔扰动触发。</p>
        <div v-else class="command-list">
          <article v-for="command in dispatch.activeCommands" :key="command.id" class="command-card">
            <header>
              <strong>{{ commandLabel(command.commandType) }}</strong>
              <span :data-status="command.status">{{ commandStatusLabel(command.status) }}</span>
            </header>
            <dl>
              <div>
                <dt>指令ID</dt>
                <dd>{{ command.id }}</dd>
              </div>
              <div>
                <dt>作用列车</dt>
                <dd>{{ command.trainId }}</dd>
              </div>
              <div>
                <dt>原因</dt>
                <dd>{{ command.reason }}</dd>
              </div>
            </dl>
            <p>{{ commandStatusHint(command.status) }}</p>
            <button
              v-if="canCancelCommand(command)"
              type="button"
              class="release-button"
              @click="cancelCommand(command.id)"
            >
              人工解限速
            </button>
          </article>
        </div>
      </section>

      <section class="debug-panel">
        <div class="panel-title">
          <h2>运动扰动</h2>
          <span>调度策略输入</span>
        </div>
        <p v-if="dispatch.openDisturbances.length === 0" class="empty">暂无打开的扰动。</p>
        <div v-else class="disturbance-list">
          <article v-for="disturbance in dispatch.openDisturbances" :key="disturbance.id">
            <strong>{{ disturbance.disturbanceType }}</strong>
            <span>{{ disturbance.status }}</span>
            <p>列车 {{ disturbance.trainId }} / 站点 {{ disturbance.stationId }}</p>
            <small>{{ disturbanceMetricText(disturbance) }}</small>
          </article>
        </div>
        <div class="profile-observation">
          <h3>调度间隔观测</h3>
          <article v-for="profile in dispatch.trainProfiles" :key="profile.trainId">
            <strong>{{ profile.trainId }} 相对前车 {{ profile.frontTrainId || '-' }}</strong>
            <span>{{ headwayStateLabel(profile.headwayState) }}</span>
            <small>
              实际 {{ profile.headwayActualSeconds === null ? '-' : formatNumber(profile.headwayActualSeconds, 0) }}s
              / 偏差 {{ profile.headwayDeviationSeconds }}s
              / 建议 {{ headwayActionLabel(profile.headwayAction) }}
              / 停站偏差 {{ profile.dwellDeviationSeconds }}s
            </small>
          </article>
          <p v-if="dispatch.trainProfiles.length === 0" class="empty">暂无调度观测数据。</p>
        </div>
      </section>
    </section>

    <section class="debug-panel comparison-panel">
      <div class="panel-title">
        <h2>调度前后对比</h2>
        <span v-if="comparisonBaseline">
          {{ commandLabel(comparisonBaseline.commandType) }} / {{ comparisonBaseline.trainId }} / tick {{ comparisonBaseline.capturedTick }}
        </span>
        <span v-else>发送演示指令时自动记录基线</span>
      </div>
      <p v-if="!comparisonBaseline" class="empty">
        暂无对比数据。点击“发送限速闭环”“拉开后车间隔”或“催发后车追赶”后，这里会记录调度前状态并与当前状态实时比较。
      </p>
      <div v-else-if="comparisonWarnings.length > 0" class="comparison-warnings">
        <p v-for="warning in comparisonWarnings" :key="warning">{{ warning }}</p>
      </div>
      <div v-else class="comparison-grid">
        <article v-for="row in comparisonRows" :key="row.label">
          <span>{{ row.label }}</span>
          <strong>{{ row.before }} -> {{ row.after }}</strong>
          <small>{{ row.delta }}</small>
        </article>
      </div>
      <div v-if="comparisonBaseline && comparisonWarnings.length > 0" class="comparison-grid">
        <article v-for="row in comparisonRows" :key="row.label">
          <span>{{ row.label }}</span>
          <strong>{{ row.before }} -> {{ row.after }}</strong>
          <small>{{ row.delta }}</small>
        </article>
      </div>
    </section>

    <section class="debug-panel">
      <div class="panel-title">
        <h2>列车状态与调度观测点</h2>
        <span>判断指令是否作用的主要依据</span>
      </div>
      <div class="table-wrap">
        <table>
          <thead>
            <tr>
              <th>列车</th>
              <th>状态</th>
              <th>位置(m)</th>
              <th>速度(m/s)</th>
              <th>站点</th>
              <th>停站(s)</th>
              <th>限速(m/s)</th>
              <th>终点剩余(m)</th>
              <th>MA距离(m)</th>
              <th>车辆约束</th>
              <th>牵引/制动</th>
              <th>满载率</th>
            </tr>
          </thead>
          <tbody>
            <tr v-if="trains.length === 0">
              <td colspan="12">暂无列车数据。</td>
            </tr>
            <tr v-for="train in trains" :key="train.id">
              <td>{{ train.id }}</td>
              <td>{{ trainStatusLabel(train.status) }}</td>
              <td>{{ formatNumber(train.positionMeters) }}</td>
              <td>{{ formatNumber(train.speedMetersPerSecond) }}</td>
              <td>{{ stationObservation(train.id, train.currentStationId) }}</td>
              <td>{{ train.dwellElapsedSeconds ?? 0 }}</td>
              <td>{{ formatNumber(train.speedLimitMetersPerSecond) }}</td>
              <td>{{ formatNumber(remainingToTerminal(train)) }}</td>
              <td>{{ formatNumber(train.movementAuthorityDistanceMeters) }}</td>
              <td>{{ dynamicsReasonLabel(train.dynamicsConstraintReason || '-') }}</td>
              <td>{{ train.tractionState }} / {{ train.brakeState }}</td>
              <td>{{ formatPercent(train.loadRate) }}</td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>

    <section class="debug-grid three">
      <section class="debug-panel">
        <div class="panel-title">
          <h2>信号 MA</h2>
          <span>调度到车辆的中间约束</span>
        </div>
        <div class="compact-list">
          <article v-for="authority in authorities" :key="authority.trainId">
            <strong>{{ authority.trainId }}</strong>
            <span>{{ formatNumber(authority.speedLimitMetersPerSecond) }} m/s</span>
            <small>{{ authority.currentSegmentId }} / 到 {{ formatNumber(authority.authorityEndMeters) }}m</small>
            <p>{{ authority.reason }}</p>
          </article>
          <p v-if="authorities.length === 0" class="empty">暂无 MA 数据。</p>
        </div>
      </section>

      <section class="debug-panel">
        <div class="panel-title">
          <h2>进路</h2>
          <span>道岔调度相关状态</span>
        </div>
        <div class="route-control-bar">
          <select v-model="selectedRouteId" aria-label="选择进路">
            <option value="">选择进路</option>
            <option v-for="route in dispatchRouteList" :key="route.routeId" :value="route.routeId">
              {{ route.routeId }} · {{ route.name }} · {{ route.status }}
            </option>
          </select>
          <select v-model="selectedRouteTrainId" aria-label="选择列车">
            <option value="">默认首列车</option>
            <option v-for="train in trains" :key="train.id" :value="train.id">{{ train.id }}</option>
          </select>
          <button
            type="button"
            :disabled="!selectedRouteId || trains.length === 0 || routeRequestPending"
            @click="requestSelectedRoute"
          >
            {{ routeRequestPending ? '提交中' : '申请进路' }}
          </button>
          <button type="button" class="ghost-button" @click="loadDispatchRoutes">刷新</button>
        </div>
        <p v-if="routeOperationMessage" class="route-operation-message">{{ routeOperationMessage }}</p>
        <div v-if="dispatch.routeDecisions.length > 0" class="route-trace-list">
          <article v-for="decision in dispatch.routeDecisions" :key="decision.decisionId">
            <strong>决策 {{ decision.decisionId }}</strong>
            <span>{{ decision.status }}</span>
            <small>指令 {{ decision.routeCommandId }} / 进路 {{ decision.selectedRouteId }}</small>
            <p>
              预留 {{ routeReservationByDecision.get(decision.decisionId)?.reservationId || '-' }}
              / {{ routeReservationByDecision.get(decision.decisionId)?.state || '等待处理' }}
            </p>
          </article>
        </div>
        <div class="compact-list">
          <article v-for="route in routes" :key="route.routeId">
            <strong>{{ route.routeId }}</strong>
            <span>{{ routeStatusLabel(route.status) }}</span>
            <small>列车 {{ route.establishedByTrainId || '-' }}</small>
            <p>锁闭道岔 {{ route.lockedSwitchIds.length }} 个 / 轴占区段 {{ route.axleSegmentIds.length }} 个</p>
          </article>
          <article v-for="route in dispatchRouteList" :key="`list-${route.routeId}`">
            <strong>{{ route.routeId }}</strong>
            <span>{{ routeStatusLabel(route.status) }}</span>
            <small>{{ route.fromStation }} -> {{ route.toStation }}</small>
            <p>{{ route.type }} / {{ route.segmentIds.length }} 个区段</p>
          </article>
          <p v-if="routes.length === 0 && dispatchRouteList.length === 0" class="empty">暂无进路数据。</p>
        </div>
      </section>

      <section class="debug-panel">
        <div class="panel-title">
          <h2>道岔与信号机</h2>
          <span>联锁输出</span>
        </div>
        <div class="switch-signal-grid">
          <div>
            <h3>道岔</h3>
            <article v-for="item in switches.slice(0, 8)" :key="item.id">
              <strong>{{ item.id }}</strong>
              <span>{{ item.position === 'NORMAL' ? '定位' : '反位' }}</span>
              <small>{{ item.locked ? '锁闭' : '未锁闭' }} / {{ item.activeSegmentId }}</small>
            </article>
            <p v-if="switches.length === 0" class="empty">暂无道岔数据。</p>
          </div>
          <div>
            <h3>信号机</h3>
            <article v-for="signal in signals.slice(0, 8)" :key="signal.signalId">
              <strong>{{ signal.signalId }}</strong>
              <span :data-aspect="signal.aspect">{{ signalAspectLabel(signal.aspect) }}</span>
              <small>{{ signal.segmentId }} / {{ signal.reasonTrainId || '-' }}</small>
            </article>
            <p v-if="signals.length === 0" class="empty">暂无信号数据。</p>
          </div>
        </div>
      </section>
    </section>
  </main>
</template>

<style scoped>
.debug-shell {
  min-height: 100vh;
  padding: 20px;
  background: #f6f8fb;
  color: #172033;
}

.debug-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  max-width: 1500px;
  margin: 0 auto 16px;
}

.eyebrow {
  margin: 0 0 4px;
  color: #2563eb;
  font-size: 12px;
  font-weight: 700;
  letter-spacing: 0;
  text-transform: uppercase;
}

h1,
h2,
h3,
p {
  margin: 0;
}

.debug-header h1 {
  font-size: 28px;
  line-height: 1.2;
}

.debug-header p:last-child {
  margin-top: 6px;
  color: #64748b;
}

.debug-actions {
  display: flex;
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: 8px;
}

button {
  border: 1px solid #cbd5e1;
  border-radius: 8px;
  background: #fff;
  color: #1e293b;
  min-height: 36px;
  padding: 8px 12px;
  font-weight: 600;
  cursor: pointer;
}

button.active {
  border-color: #059669;
  background: #059669;
  color: #fff;
}

button:disabled {
  cursor: not-allowed;
  opacity: 0.55;
}

.ghost-button {
  border-color: #2563eb;
  color: #1d4ed8;
}

.demo-button {
  border-color: #7c3aed;
  background: #7c3aed;
  color: #fff;
}

.release-button {
  width: fit-content;
  border-color: #dc2626;
  background: #fff;
  color: #b91c1c;
}

.demo-control-bar {
  display: grid;
  grid-template-columns: minmax(130px, 1fr) auto minmax(150px, 1fr) auto minmax(150px, 1fr) auto;
  align-items: end;
  gap: 8px;
  margin-bottom: 12px;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  background: #f8fafc;
  padding: 10px;
}

.demo-control-bar label {
  display: grid;
  gap: 4px;
  min-width: 0;
}

.demo-control-bar label span {
  color: #64748b;
  font-size: 12px;
  font-weight: 700;
}

.demo-control-bar input {
  width: 100%;
  min-height: 36px;
  border: 1px solid #cbd5e1;
  border-radius: 8px;
  padding: 6px 10px;
  font: inherit;
}

.route-control-bar {
  display: grid;
  grid-template-columns: minmax(160px, 1.2fr) minmax(120px, 0.8fr) auto auto auto;
  gap: 8px;
  margin-bottom: 10px;
}

.route-control-bar select {
  min-height: 36px;
  border: 1px solid #cbd5e1;
  border-radius: 8px;
  padding: 6px 10px;
  font: inherit;
}

.route-operation-message {
  margin: 0 0 10px;
  color: #1d4ed8;
  font-size: 13px;
}

.route-trace-list {
  display: grid;
  gap: 8px;
  margin-bottom: 10px;
}

.route-trace-list article {
  display: grid;
  gap: 4px;
  border-left: 3px solid #2563eb;
  background: #eff6ff;
  padding: 8px 10px;
}

.route-trace-list p {
  color: #475569;
  font-size: 12px;
}

.debug-banner,
.metric-grid,
.debug-grid,
.debug-panel {
  max-width: 1500px;
  margin-left: auto;
  margin-right: auto;
}

.debug-banner {
  margin-bottom: 12px;
  border-radius: 8px;
  padding: 10px 12px;
  font-size: 13px;
}

.debug-banner.error {
  background: #fef2f2;
  color: #b91c1c;
}

.debug-banner.warn {
  background: #fffbeb;
  color: #92400e;
}

.metric-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 12px;
  margin-bottom: 12px;
}

.metric-grid article,
.debug-panel {
  border: 1px solid #d9e2ef;
  border-radius: 8px;
  background: #fff;
  box-shadow: 0 8px 24px rgba(15, 23, 42, 0.05);
}

.metric-grid article {
  display: grid;
  gap: 4px;
  min-height: 88px;
  padding: 14px;
}

.metric-grid span,
.metric-grid small,
.panel-title span,
.empty,
dt,
small {
  color: #64748b;
}

.metric-grid strong {
  font-size: 22px;
}

.headway-focus {
  max-width: 1500px;
  margin: 0 auto 12px;
  border: 1px solid #bfdbfe;
  border-radius: 8px;
  background: #f8fbff;
  padding: 14px;
  box-shadow: 0 8px 24px rgba(15, 23, 42, 0.05);
}

.headway-kpi-grid {
  display: grid;
  grid-template-columns: 1.1fr repeat(4, minmax(0, 1fr));
  gap: 10px;
}

.headway-kpi {
  display: grid;
  align-content: start;
  gap: 6px;
  min-height: 96px;
  min-width: 0;
  border: 1px solid #dbeafe;
  border-radius: 8px;
  background: #fff;
  padding: 12px;
}

.headway-kpi span {
  color: #475569;
  font-size: 12px;
  font-weight: 700;
}

.headway-kpi strong {
  overflow-wrap: anywhere;
  color: #172033;
  font-size: 20px;
  line-height: 1.25;
}

.headway-kpi small {
  line-height: 1.35;
}

.headway-kpi.controlled {
  border-color: #c7d2fe;
  background: #eef2ff;
}

.headway-kpi.highlight {
  border-color: #f59e0b;
  background: #fffbeb;
}

.headway-kpi.highlight strong {
  font-size: 26px;
}

.headway-focus[data-state='TOO_SHORT'] .headway-kpi.highlight,
.headway-focus[data-state='TOO_LONG'] .headway-kpi.highlight {
  border-color: #ef4444;
  background: #fef2f2;
}

.headway-focus[data-state='TOO_SHORT'] .headway-kpi.highlight strong,
.headway-focus[data-state='TOO_LONG'] .headway-kpi.highlight strong {
  color: #b91c1c;
}

.headway-focus[data-state='ON_TARGET'] .headway-kpi.highlight {
  border-color: #10b981;
  background: #ecfdf5;
}

.headway-focus[data-state='ON_TARGET'] .headway-kpi.highlight strong {
  color: #047857;
}

.headway-focus-hint {
  margin-top: 10px;
  color: #475569;
  font-size: 13px;
  line-height: 1.5;
}

.debug-grid {
  display: grid;
  grid-template-columns: minmax(0, 1.4fr) minmax(320px, 0.6fr);
  gap: 12px;
  margin-bottom: 12px;
}

.debug-grid.three {
  grid-template-columns: repeat(3, minmax(0, 1fr));
}

.debug-panel {
  padding: 14px;
}

.panel-title {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 12px;
}

.panel-title h2 {
  font-size: 16px;
}

.command-list,
.disturbance-list,
.compact-list {
  display: grid;
  gap: 10px;
}

.command-card,
.disturbance-list article,
.compact-list article {
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  background: #f8fafc;
  padding: 10px;
}

.command-card header,
.disturbance-list article,
.compact-list article {
  display: grid;
  gap: 6px;
}

.command-card header {
  grid-template-columns: minmax(0, 1fr) auto;
  align-items: center;
}

.command-card dl {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 8px;
  margin: 10px 0;
}

dt {
  font-size: 12px;
}

dd {
  margin: 2px 0 0;
  overflow-wrap: anywhere;
}

.command-card p,
.disturbance-list p,
.compact-list p {
  color: #475569;
  font-size: 13px;
  line-height: 1.5;
}

.profile-observation {
  display: grid;
  gap: 8px;
  margin-top: 14px;
  border-top: 1px solid #e2e8f0;
  padding-top: 12px;
}

.profile-observation h3 {
  font-size: 14px;
}

.profile-observation article {
  display: grid;
  gap: 4px;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  background: #f8fafc;
  padding: 8px;
}

.comparison-panel {
  margin-bottom: 12px;
}

.comparison-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 10px;
}

.comparison-warnings {
  display: grid;
  gap: 6px;
  margin-bottom: 10px;
  border: 1px solid #fde68a;
  border-radius: 8px;
  background: #fffbeb;
  padding: 10px 12px;
}

.comparison-warnings p {
  color: #92400e;
  font-size: 13px;
  line-height: 1.5;
}

.comparison-grid article {
  display: grid;
  gap: 6px;
  min-height: 92px;
  border: 1px solid #dbeafe;
  border-radius: 8px;
  background: #f8fbff;
  padding: 12px;
}

.comparison-grid article span {
  color: #475569;
  font-size: 12px;
  font-weight: 700;
}

.comparison-grid article strong {
  overflow-wrap: anywhere;
  color: #172033;
  font-size: 15px;
  line-height: 1.35;
}

.comparison-grid article small {
  width: fit-content;
  border-radius: 999px;
  background: #e0f2fe;
  color: #0369a1;
  padding: 3px 8px;
  font-weight: 700;
}

[data-status],
[data-aspect],
.disturbance-list span,
.compact-list span {
  width: fit-content;
  border-radius: 999px;
  padding: 3px 8px;
  background: #e2e8f0;
  color: #334155;
  font-size: 12px;
  font-weight: 700;
}

[data-status='PENDING'] {
  background: #fef3c7;
  color: #92400e;
}

[data-status='SENT'] {
  background: #dbeafe;
  color: #1d4ed8;
}

[data-status='APPLIED'] {
  background: #dcfce7;
  color: #166534;
}

[data-status='EFFECT_CONFIRMED'] {
  background: #ccfbf1;
  color: #0f766e;
}

[data-status='TIMEOUT'],
[data-status='REJECTED'],
[data-status='SKIPPED'],
[data-aspect='RED'] {
  background: #fee2e2;
  color: #b91c1c;
}

[data-status='RELEASED'],
[data-status='CANCELLED'],
[data-status='EXPIRED'] {
  background: #e2e8f0;
  color: #475569;
}

[data-aspect='YELLOW'] {
  background: #fef3c7;
  color: #92400e;
}

[data-aspect='GREEN'] {
  background: #dcfce7;
  color: #166534;
}

.table-wrap {
  overflow-x: auto;
}

table {
  width: 100%;
  min-width: 1100px;
  border-collapse: collapse;
  font-size: 13px;
}

th,
td {
  border-bottom: 1px solid #eef2f7;
  padding: 8px 6px;
  text-align: left;
  white-space: nowrap;
}

th {
  color: #475569;
  font-weight: 700;
}

.switch-signal-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px;
}

.switch-signal-grid h3 {
  margin-bottom: 8px;
  font-size: 14px;
}

.switch-signal-grid article {
  display: grid;
  gap: 4px;
  border-bottom: 1px solid #eef2f7;
  padding: 7px 0;
}

.empty {
  font-size: 13px;
}

@media (max-width: 1100px) {
  .debug-header {
    display: grid;
  }

  .debug-actions {
    justify-content: flex-start;
  }

  .metric-grid,
  .headway-kpi-grid,
  .debug-grid,
  .debug-grid.three,
  .comparison-grid {
    grid-template-columns: 1fr;
  }

  .command-card dl,
  .switch-signal-grid,
  .demo-control-bar,
  .route-control-bar {
    grid-template-columns: 1fr;
  }
}
</style>
