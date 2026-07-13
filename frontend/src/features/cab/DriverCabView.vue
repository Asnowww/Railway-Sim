<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { storeToRefs } from 'pinia'
import { useSimulationStore } from '../../stores/simulation'
import { useConnectionStore } from '../../stores/connection'
import { useTopologyStore } from '../../stores/topology'
import { signalVehicleApi } from '../../api/signalVehicle'
import { vehicleApi, type DriverCabPlcInputPacket } from '../../api/vehicle'
import Panel from '../../shared/components/Panel.vue'
import StatusBadge from '../../shared/components/StatusBadge.vue'
import SpeedGauge from './SpeedGauge.vue'
import { toastError } from '../../shared/toast'
import {
  brakeStateLabel,
  currentCollectionLabel,
  dataQualityLabel,
  dataQualityTone,
  departureInfoLabel,
  directionHandleLabel,
  doorControlModeLabel,
  doorSideLabel,
  doorStateLabel,
  drivingModeLabel,
  masterHandleLabel,
  tractionBrakeInfoLabel,
  tractionStateLabel,
  turnbackInfoLabel,
  vehicleProtectionLabel
} from '../../shared/labels'
import type { DriverCommandAcceptance, SignalVehicleCommand, VehicleSignalStatus } from '../../types/vehicles'

const route = useRoute()
const simulation = useSimulationStore()
const connection = useConnectionStore()
const topology = useTopologyStore()
const { trains, authorities } = storeToRefs(simulation)

/* ---------------- 选车 ---------------- */

const selectedTrainId = ref<string>((route.query.train as string) ?? '')

watch(
  trains,
  (list) => {
    if (!selectedTrainId.value && list.length > 0) selectedTrainId.value = list[0]!.id
  },
  { immediate: true }
)

const train = computed(() => trains.value.find((t) => t.id === selectedTrainId.value) ?? null)
const authority = computed(() => authorities.value.find((a) => a.trainId === selectedTrainId.value) ?? null)

/* ---------------- DMI 数据（cabDisplay / driverConsoleState 轮询） ---------------- */

const commands = ref<SignalVehicleCommand[]>([])
const statuses = ref<VehicleSignalStatus[]>([])
let dmiTimer = 0

async function pollDmi(): Promise<void> {
  const [commandsResult, statusesResult] = await Promise.allSettled([
    signalVehicleApi.commands(),
    signalVehicleApi.statuses()
  ])
  if (commandsResult.status === 'fulfilled') commands.value = commandsResult.value
  if (statusesResult.status === 'fulfilled') statuses.value = statusesResult.value
}

const command = computed(() => commands.value.find((c) => c.trainId === selectedTrainId.value) ?? null)
const cab = computed(() => command.value?.cabDisplay ?? null)
const signalStatus = computed(() => statuses.value.find((s) => s.trainId === selectedTrainId.value) ?? null)
const consoleEcho = computed(() => signalStatus.value?.driverConsoleState ?? null)

/* ---------------- 速度表数据 ---------------- */

const speedKph = computed(() => (train.value ? train.value.speedMetersPerSecond * 3.6 : 0))
const permittedKph = computed(() => {
  const cabLimit = cab.value?.speedLimitMetersPerSecond
  const trainLimit = train.value?.speedLimitMetersPerSecond
  const limit = cabLimit ?? trainLimit ?? 0
  return limit * 3.6
})

/* ---------------- MA / 车站 距离条 ---------------- */

const maRemaining = computed(() => Math.max(0, train.value?.movementAuthorityDistanceMeters ?? 0))
const stationDistance = computed(() => {
  const fromCab = cab.value?.distanceToNextStationMeters
  const fromTrain = train.value?.stationDistanceMeters
  const value = fromCab ?? fromTrain ?? 0
  return value >= 9000 ? null : Math.max(0, value) // 9999 哨兵值 = 无前方车站
})
const stoppingDistance = computed(() => Math.max(0, train.value?.stoppingDistanceMeters ?? 0))

const distanceScale = computed(() => {
  const candidates = [maRemaining.value, stationDistance.value ?? 0, stoppingDistance.value, 200]
  const max = Math.max(...candidates)
  return Math.ceil(max / 100) * 100 * 1.15
})

function distancePercent(meters: number): number {
  if (distanceScale.value <= 0) return 0
  return Math.min(100, (meters / distanceScale.value) * 100)
}

/* ---------------- 状态灯矩阵 ---------------- */

interface Lamp {
  key: string
  label: string
  value: string
  tone: 'ok' | 'warn' | 'danger' | 'fault' | 'info' | 'neutral' | 'stale'
  blink?: boolean
}

const lamps = computed<Lamp[]>(() => {
  const t = train.value
  const c = command.value
  const d = cab.value
  if (!t) return []
  const doorClosed = t.doorState === 'CLOSED_LOCKED' || t.doorState === 'CLOSED'
  return [
    {
      key: 'eb',
      label: '紧急制动',
      value: c?.emergencyBrakeCommand || d?.emergencyBrake ? '施加' : '未施加',
      tone: c?.emergencyBrakeCommand || d?.emergencyBrake ? 'danger' : 'neutral',
      blink: Boolean(c?.emergencyBrakeCommand || d?.emergencyBrake)
    },
    {
      key: 'sb',
      label: '常用制动令',
      value: c?.serviceBrakeCommand ? '施加' : '未施加',
      tone: c?.serviceBrakeCommand ? 'warn' : 'neutral'
    },
    {
      key: 'tc',
      label: '牵引切除',
      value: c?.tractionCutoff ? '已切除' : '正常',
      tone: c?.tractionCutoff ? 'warn' : 'ok'
    },
    {
      key: 'door',
      label: '车门',
      value: doorStateLabel(t.doorState),
      tone: doorClosed ? 'ok' : 'warn'
    },
    {
      key: 'doorEnable',
      label: '门使能',
      value: d?.doorEnable ? doorSideLabel(d.doorEnable.side) : '—',
      tone: d?.doorEnable && d.doorEnable.side !== 'NONE' ? 'info' : 'neutral'
    },
    {
      key: 'collection',
      label: '受流',
      value: currentCollectionLabel(t.currentCollectionStatus),
      tone: t.currentCollectionStatus === 'NORMAL' ? 'ok' : 'danger'
    },
    {
      key: 'traction',
      label: '牵引系统',
      value: t.tractionAvailable ? `可用 ${t.availableTractionCount}` : '不可用',
      tone: t.tractionAvailable ? 'ok' : 'danger'
    },
    {
      key: 'brake',
      label: '制动系统',
      value: t.brakeAvailable ? `可用 ${t.availableBrakeCount}` : '不可用',
      tone: t.brakeAvailable ? 'ok' : 'danger'
    },
    {
      key: 'selfcheck',
      label: '自检',
      value: t.selfCheckStatus,
      tone: t.selfCheckStatus === 'PASS' ? 'ok' : t.selfCheckStatus === 'WARN' ? 'warn' : 'danger'
    },
    {
      key: 'depart',
      label: '发车许可',
      value: departureInfoLabel(d?.departureInfo),
      tone: d?.departureInfo === 'DEPART' ? 'ok' : d?.departureInfo === 'HOLD' ? 'warn' : 'neutral'
    },
    {
      key: 'turnback',
      label: '折返',
      value: turnbackInfoLabel(d?.turnbackInfo),
      tone: d?.turnbackInfo === 'ACTIVE' ? 'info' : 'neutral'
    },
    {
      key: 'zero',
      label: '零速',
      value: t.zeroSpeed ? '零速' : '走行',
      tone: t.zeroSpeed ? 'info' : 'neutral'
    },
    {
      key: 'protection',
      label: '车辆防护',
      value: vehicleProtectionLabel(t.vehicleProtectionReason),
      tone: t.vehicleProtectionReason && t.vehicleProtectionReason !== 'NONE' ? 'danger' : 'ok',
      blink: Boolean(t.vehicleProtectionReason && t.vehicleProtectionReason !== 'NONE')
    },
    {
      key: 'fault',
      label: '故障',
      value: t.faultCode && t.faultCode !== 'OK' ? `${t.faultCode} L${t.faultLevel}` : '正常',
      tone: t.faultCode && t.faultCode !== 'OK' ? (t.faultLevel >= 3 ? 'danger' : 'warn') : 'ok'
    }
  ]
})

/* ---------------- 司控台（写入 → plc-input 网关） ---------------- */

const console_ = reactive({
  keyActive: true,
  directionHandleState: 'FORWARD' as 'FORWARD' | 'ZERO' | 'BACKWARD',
  /** -100（全制动）～ +100（全牵引），0=零位 */
  handlePosition: 0,
  fastBrake: false,
  emergencyBrake: false,
  doorsClosed: true,
  doorModeSwitchState: 'SEMI_AUTOMATIC' as 'AUTOMATIC' | 'SEMI_AUTOMATIC' | 'MANUAL',
  atoModeActive: true,
  autoSend: true
})

const masterHandleState = computed<'TRACTION' | 'ZERO' | 'BRAKE' | 'FAST_BRAKE'>(() => {
  if (console_.fastBrake) return 'FAST_BRAKE'
  if (console_.handlePosition > 0) return 'TRACTION'
  if (console_.handlePosition < 0) return 'BRAKE'
  return 'ZERO'
})

const sending = ref(false)
const lastAcceptance = ref<DriverCommandAcceptance | null>(null)
const lastSendError = ref('')
let sendDebounce = 0

function buildPacket(options: { atoStart?: boolean; modeUp?: boolean; modeDown?: boolean } = {}): DriverCabPlcInputPacket {
  const position = console_.handlePosition
  return {
    highVoltageClosedIndicator: train.value?.currentCollectionStatus !== 'LOST',
    doorsClosedLockedIndicator: console_.doorsClosed,
    networkFaultIndicator: false,
    automaticTurnbackAvailable: cab.value?.turnbackInfo === 'AVAILABLE',
    atoModeAvailable: true,
    atoModeActive: console_.atoModeActive,
    automaticTurnbackActive: cab.value?.turnbackInfo === 'ACTIVE',
    emergencyBrakeButtonLocked: console_.emergencyBrake,
    openLeftDoorFlag: !console_.doorsClosed,
    openRightDoorFlag: !console_.doorsClosed,
    closeLeftDoorFlag: console_.doorsClosed,
    closeRightDoorFlag: console_.doorsClosed,
    doorModeSwitchState: console_.doorModeSwitchState,
    modeUpgradeConfirmFlag: Boolean(options.modeUp),
    modeDowngradeConfirmFlag: Boolean(options.modeDown),
    automaticTurnbackFlag: false,
    atoStartFlag: Boolean(options.atoStart),
    // 9300 语义：keySwitchLocked=true 表示钥匙插入锁定（激活）；false 触发 DRIVER_CAB_KEY_OFF 车辆防护
    keySwitchLocked: console_.keyActive,
    directionHandleState: console_.directionHandleState,
    masterHandleState: masterHandleState.value,
    tractionNotchPercent: console_.fastBrake ? 0 : Math.max(0, Math.round(position)),
    brakeNotchPercent: console_.fastBrake ? 100 : Math.max(0, Math.round(-position))
  }
}

async function sendPacket(options: { atoStart?: boolean; modeUp?: boolean; modeDown?: boolean } = {}): Promise<void> {
  if (!selectedTrainId.value || sending.value) return
  sending.value = true
  lastSendError.value = ''
  try {
    lastAcceptance.value = await vehicleApi.driverCabPlcInput(selectedTrainId.value, buildPacket(options))
    if (lastAcceptance.value && !lastAcceptance.value.accepted) {
      lastSendError.value = `命令被拒绝：${lastAcceptance.value.reasonCode ?? 'UNKNOWN'}`
    }
  } catch (error) {
    lastSendError.value = error instanceof Error ? error.message : String(error)
    toastError('司控台命令发送失败', error)
  } finally {
    sending.value = false
  }
}

/** 操作即发送（模拟真实司机台的周期上报，300ms 防抖合并连续手柄动作） */
function queueSend(): void {
  if (!console_.autoSend) return
  window.clearTimeout(sendDebounce)
  sendDebounce = window.setTimeout(() => void sendPacket(), 300)
}

watch(
  () => [
    console_.keyActive,
    console_.directionHandleState,
    console_.handlePosition,
    console_.fastBrake,
    console_.emergencyBrake,
    console_.doorsClosed,
    console_.doorModeSwitchState,
    console_.atoModeActive
  ],
  queueSend
)

function releaseHandle(): void {
  console_.handlePosition = 0
  console_.fastBrake = false
}

function atoStart(): void {
  console_.atoModeActive = true
  void sendPacket({ atoStart: true })
}

/* ---------------- PLC 输出帧监视（26 字节，8080 编码） ---------------- */

const plcOutputHex = ref<string[]>([])
const plcOutputError = ref('')
let plcTimer = 0

async function pollPlcOutput(): Promise<void> {
  if (!selectedTrainId.value) return
  try {
    const buffer = await vehicleApi.driverCabPlcOutput(selectedTrainId.value)
    plcOutputHex.value = [...new Uint8Array(buffer)].map((b) => b.toString(16).padStart(2, '0').toUpperCase())
    plcOutputError.value = ''
  } catch (error) {
    plcOutputHex.value = []
    plcOutputError.value = error instanceof Error ? error.message : String(error)
  }
}

/* ---------------- 生命周期 ---------------- */

onMounted(() => {
  void pollDmi()
  void pollPlcOutput()
  dmiTimer = window.setInterval(() => void pollDmi(), 2000)
  plcTimer = window.setInterval(() => void pollPlcOutput(), 2000)
})

onBeforeUnmount(() => {
  window.clearInterval(dmiTimer)
  window.clearInterval(plcTimer)
  window.clearTimeout(sendDebounce)
})

watch(selectedTrainId, () => {
  lastAcceptance.value = null
  void pollPlcOutput()
})

const stale = computed(() => !connection.dataTrusted)

function fmtForce(newtons?: number): string {
  if (newtons === undefined || newtons === null) return '—'
  return `${Math.round(newtons / 1000)} kN`
}
</script>

<template>
  <div class="cab-view">
    <!-- 顶部：选车 + 模式横幅 -->
    <div class="cab-topstrip">
      <label class="field train-picker">
        <span>司机台车次</span>
        <select v-model="selectedTrainId">
          <option v-for="t in trains" :key="t.id" :value="t.id">{{ t.id }}（{{ t.serviceNo || '—' }}）</option>
        </select>
      </label>

      <div v-if="cab" class="mode-banner">
        <div class="mode-block">
          <span class="mode-tag">当前模式</span>
          <strong class="mode-value">{{ cab.currentDrivingMode ?? '—' }}</strong>
          <span class="mode-full">{{ drivingModeLabel(cab.currentDrivingMode) }}</span>
        </div>
        <div class="mode-block dim">
          <span class="mode-tag">最高可用</span>
          <strong class="mode-value">{{ cab.maximumAvailableDrivingMode ?? '—' }}</strong>
        </div>
        <StatusBadge
          :tone="cab.departureInfo === 'DEPART' ? 'ok' : 'warn'"
          :label="departureInfoLabel(cab.departureInfo)"
        />
        <StatusBadge tone="info" :label="`门控 ${doorControlModeLabel(cab.doorControlMode)}`" />
        <StatusBadge
          :tone="cab.tractionBrakeInfo === 'EMERGENCY_BRAKING' ? 'danger' : cab.tractionBrakeInfo === 'BRAKING' ? 'warn' : 'ok'"
          :label="`工况 ${tractionBrakeInfoLabel(cab.tractionBrakeInfo)}`"
          :blink="cab.tractionBrakeInfo === 'EMERGENCY_BRAKING'"
        />
      </div>
      <div v-else class="mode-banner muted-text">等待 DMI 数据（/api/signal/vehicles/commands）…</div>

      <StatusBadge v-if="train" :tone="dataQualityTone(train.dataQuality)" :label="`数据 ${dataQualityLabel(train.dataQuality)}`" />
    </div>

    <div v-if="!train" class="cab-empty">
      <Panel title="司机驾驶台">
        <p class="muted-text">暂无列车。启动仿真并等待列车注册后进入司机台。</p>
      </Panel>
    </div>

    <div v-else class="cab-grid">
      <!-- 左：速度表 + 距离条 -->
      <Panel :title="`速度监督 · ${train.id}`" :stale="stale" :stale-since="connection.lastSnapshotClock">
        <div class="gauge-wrap">
          <SpeedGauge :speed="speedKph" :permitted="permittedKph" :size="320" />
          <div class="gauge-side">
            <div class="gauge-stat">
              <span>允许速度</span>
              <strong class="num ok-text">{{ Math.round(permittedKph) }} <small>km/h</small></strong>
            </div>
            <div class="gauge-stat">
              <span>加速度</span>
              <strong class="num">{{ (train.accelerationMetersPerSecondSquared ?? 0).toFixed(2) }} <small>m/s²</small></strong>
            </div>
            <div class="gauge-stat">
              <span>工况</span>
              <strong>{{ tractionStateLabel(train.tractionState) }} / {{ brakeStateLabel(train.brakeState) }}</strong>
            </div>
            <div class="gauge-stat">
              <span>方向</span>
              <strong>{{ train.direction }}</strong>
            </div>
          </div>
        </div>

        <!-- ETCS 风格距离条 -->
        <div class="distance-bar" role="img" :aria-label="`移动授权余量 ${Math.round(maRemaining)} 米`">
          <div class="db-track">
            <div class="db-ma" :style="{ width: `${distancePercent(maRemaining)}%` }"></div>
            <div class="db-stop-mark" :style="{ left: `${distancePercent(stoppingDistance)}%` }" title="制动停车距离"></div>
            <div
              v-if="stationDistance !== null"
              class="db-station-mark"
              :style="{ left: `${distancePercent(stationDistance)}%` }"
              :title="`前方车站 ${Math.round(stationDistance)}m`"
            >⌂</div>
            <div class="db-ma-end" :style="{ left: `${distancePercent(maRemaining)}%` }" title="MA 终点"></div>
          </div>
          <div class="db-legend">
            <span>MA 余量 <strong class="num">{{ Math.round(maRemaining) }} m</strong></span>
            <span>制动距离 <strong class="num">{{ Math.round(stoppingDistance) }} m</strong></span>
            <span v-if="stationDistance !== null">
              前方车站 <strong class="num">{{ Math.round(stationDistance) }} m</strong>
            </span>
            <span v-else class="muted-text">前方无车站目标</span>
            <span v-if="train.currentStationId">
              停站 {{ topology.stationName(train.currentStationId) }}
              <strong class="num">{{ train.dwellElapsedSeconds ?? 0 }}s</strong>
            </span>
          </div>
          <p v-if="authority" class="ma-reason">MA：{{ authority.reason }}（终点 {{ Math.round(authority.authorityEndMeters) }}m · 区段 {{ authority.currentSegmentId }}）</p>
        </div>

        <!-- 遥测行 -->
        <div class="telemetry-row">
          <div class="tele"><span>牵引力</span><strong class="num">{{ fmtForce(train.tractionForceNewtons) }}</strong></div>
          <div class="tele"><span>制动力</span><strong class="num">{{ fmtForce(train.brakeForceNewtons) }}</strong></div>
          <div class="tele"><span>网侧电流</span><strong class="num">{{ Math.round(train.railCurrentAmps ?? 0) }} A</strong></div>
          <div class="tele"><span>牵引功率</span><strong class="num">{{ Math.round((train.tractionPowerWatts ?? 0) / 1000) }} kW</strong></div>
          <div class="tele"><span>累计能耗</span><strong class="num">{{ (train.energyConsumedKwh ?? 0).toFixed(1) }} kWh</strong></div>
          <div class="tele"><span>载荷</span><strong class="num">{{ Math.round((train.loadRate ?? 0) * 100) }}%</strong></div>
        </div>
      </Panel>

      <!-- 中：状态灯矩阵 -->
      <Panel title="状态显示" :stale="stale" :stale-since="connection.lastSnapshotClock">
        <div class="lamp-grid">
          <div v-for="lamp in lamps" :key="lamp.key" :class="['lamp', `tone-${lamp.tone}`, { blinking: lamp.blink }]">
            <span class="lamp-label">{{ lamp.label }}</span>
            <strong class="lamp-value">{{ lamp.value }}</strong>
          </div>
        </div>

        <h4 class="section-label">PLC 回读（9300 已锁存的司控台状态）</h4>
        <div v-if="consoleEcho" class="echo-grid">
          <div><span>主手柄</span><strong>{{ masterHandleLabel(consoleEcho.masterHandleState) }}</strong></div>
          <div><span>方向手柄</span><strong>{{ directionHandleLabel(consoleEcho.directionHandleState) }}</strong></div>
          <div><span>门模式</span><strong>{{ doorControlModeLabel(consoleEcho.doorModeSwitchState) }}</strong></div>
          <div><span>ATO 启动</span><strong>{{ consoleEcho.atoStartFlag ? '已触发' : '—' }}</strong></div>
        </div>
        <p v-else class="muted-text">尚未收到司控台回读</p>

        <h4 class="section-label">PLC 输出帧（8080 编码 · 26 字节 · 2s 轮询）</h4>
        <div v-if="plcOutputHex.length" class="hex-frame mono" aria-label="PLC 输出帧十六进制">
          <span
            v-for="(byte, index) in plcOutputHex"
            :key="index"
            :class="['hex-byte', { header: index < 24, data: index >= 24 }]"
            :title="index < 24 ? `报文头 byte ${index}` : `数据区 byte ${index}`"
          >{{ byte }}</span>
        </div>
        <p v-else class="muted-text">{{ plcOutputError || '暂无输出帧' }}</p>
      </Panel>

      <!-- 右：司控台 -->
      <Panel title="司控台" subtitle="操作经 8080 编码 46 字节 PLC 帧转发 9300（权威车辆控制）">
        <div class="console">
          <div class="console-row">
            <button
              type="button"
              :class="['cab-key', { on: console_.keyActive }]"
              @click="console_.keyActive = !console_.keyActive"
            >
              🔑 钥匙 {{ console_.keyActive ? '激活' : '锁闭' }}
            </button>
            <div class="segmented" role="group" aria-label="方向手柄">
              <button
                v-for="dir in (['FORWARD', 'ZERO', 'BACKWARD'] as const)"
                :key="dir"
                type="button"
                :class="{ active: console_.directionHandleState === dir }"
                @click="console_.directionHandleState = dir"
              >
                {{ directionHandleLabel(dir) }}
              </button>
            </div>
          </div>

          <div class="handle-block">
            <div class="handle-head">
              <span>主手柄</span>
              <StatusBadge
                :tone="masterHandleState === 'TRACTION' ? 'ok' : masterHandleState === 'ZERO' ? 'neutral' : masterHandleState === 'FAST_BRAKE' ? 'danger' : 'warn'"
                :label="`${masterHandleLabel(masterHandleState)} ${console_.fastBrake ? 100 : Math.abs(Math.round(console_.handlePosition))}%`"
              />
            </div>
            <div class="handle-scale">
              <span class="hs-label brake">制动 100</span>
              <input
                v-model.number="console_.handlePosition"
                type="range"
                min="-100"
                max="100"
                step="5"
                :disabled="console_.fastBrake"
                aria-label="主手柄：负值制动，正值牵引"
              />
              <span class="hs-label traction">牵引 100</span>
            </div>
            <div class="handle-actions">
              <button type="button" class="btn sm" @click="releaseHandle">回零位</button>
              <button
                type="button"
                :class="['btn sm', console_.fastBrake ? 'primary' : '']"
                @click="console_.fastBrake = !console_.fastBrake"
              >
                快速制动 {{ console_.fastBrake ? 'ON' : '' }}
              </button>
            </div>
          </div>

          <button
            type="button"
            :class="['eb-mushroom', { pressed: console_.emergencyBrake }]"
            :aria-pressed="console_.emergencyBrake"
            @click="console_.emergencyBrake = !console_.emergencyBrake"
          >
            {{ console_.emergencyBrake ? '紧急制动 已按下（点击复位）' : '紧急制动' }}
          </button>

          <div class="console-row">
            <button type="button" :class="['cab-btn', { on: !console_.doorsClosed }]" @click="console_.doorsClosed = !console_.doorsClosed">
              🚪 {{ console_.doorsClosed ? '门关闭锁紧' : '门开启请求' }}
            </button>
            <div class="segmented" role="group" aria-label="门模式开关">
              <button
                v-for="mode in (['AUTOMATIC', 'SEMI_AUTOMATIC', 'MANUAL'] as const)"
                :key="mode"
                type="button"
                :class="{ active: console_.doorModeSwitchState === mode }"
                @click="console_.doorModeSwitchState = mode"
              >
                {{ doorControlModeLabel(mode) }}
              </button>
            </div>
          </div>

          <div class="console-row">
            <button type="button" :class="['cab-btn', { on: console_.atoModeActive }]" @click="console_.atoModeActive = !console_.atoModeActive">
              🤖 ATO {{ console_.atoModeActive ? '投入' : '切除' }}
            </button>
            <button type="button" class="btn primary" :disabled="sending" @click="atoStart">▶ ATO 发车</button>
            <button type="button" class="btn sm" :disabled="sending" @click="sendPacket({ modeUp: true })">模式升级确认</button>
            <button type="button" class="btn sm" :disabled="sending" @click="sendPacket({ modeDown: true })">模式降级确认</button>
          </div>

          <div class="send-bar">
            <label class="autosend">
              <input v-model="console_.autoSend" type="checkbox" />
              操作即发送（300ms 合并）
            </label>
            <button type="button" class="btn" :disabled="sending" @click="sendPacket()">立即发送</button>
          </div>

          <div v-if="lastAcceptance" :class="['acceptance', lastAcceptance.accepted ? 'ok' : 'bad']">
            <StatusBadge :tone="lastAcceptance.accepted ? 'ok' : 'danger'" :label="lastAcceptance.accepted ? '命令已接受' : '命令被拒绝'" />
            <span class="mono small">
              {{ lastAcceptance.commandId }} · {{ lastAcceptance.reasonCode }}
              <template v-if="lastAcceptance.decisionSource"> · 决策源 {{ lastAcceptance.decisionSource }}</template>
              <template v-if="lastAcceptance.operationMode"> · {{ lastAcceptance.operationMode }}</template>
            </span>
          </div>
          <p v-if="lastSendError" class="send-error">{{ lastSendError }}</p>
          <p class="console-hint">
            手柄命令由 9300 仲裁：非零位/紧急制动时决策源切为 DRIVER（约 5s 未续发自动过期，回到 ATO）。
          </p>
        </div>
      </Panel>
    </div>
  </div>
</template>

<style scoped>
.cab-view {
  display: flex;
  flex-direction: column;
  gap: var(--gap-lg);
}

.cab-topstrip {
  display: flex;
  align-items: center;
  gap: var(--gap-lg);
  flex-wrap: wrap;
  background: var(--bg-panel);
  border: 1px solid var(--border);
  border-radius: var(--radius-lg);
  padding: 10px 14px;
}

.train-picker {
  min-width: 180px;
}

.mode-banner {
  display: flex;
  align-items: center;
  gap: var(--gap-lg);
  flex-wrap: wrap;
  flex: 1;
}

.mode-block {
  display: flex;
  align-items: baseline;
  gap: 8px;
}

.mode-block.dim {
  opacity: 0.7;
}

.mode-tag {
  font-size: var(--fs-xs);
  color: var(--text-muted);
}

.mode-value {
  font-size: 26px;
  font-family: var(--font-mono);
  color: var(--accent);
  line-height: 1;
}

.mode-full {
  font-size: var(--fs-xs);
  color: var(--text-secondary);
}

.cab-grid {
  display: grid;
  grid-template-columns: minmax(360px, 1.2fr) minmax(300px, 1fr) minmax(300px, 0.9fr);
  gap: var(--gap-lg);
  align-items: start;
}

@media (max-width: 1400px) {
  .cab-grid {
    grid-template-columns: 1fr 1fr;
  }
}

@media (max-width: 950px) {
  .cab-grid {
    grid-template-columns: 1fr;
  }
}

/* ---- 速度表区 ---- */
.gauge-wrap {
  display: flex;
  align-items: center;
  gap: var(--gap-lg);
  flex-wrap: wrap;
  justify-content: center;
}

.gauge-side {
  display: flex;
  flex-direction: column;
  gap: var(--gap-md);
  min-width: 130px;
}

.gauge-stat {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.gauge-stat span {
  font-size: var(--fs-xs);
  color: var(--text-muted);
}

.gauge-stat strong {
  font-size: var(--fs-md);
}

.gauge-stat small {
  color: var(--text-muted);
  font-size: var(--fs-xs);
}

.ok-text {
  color: var(--status-ok);
}

/* ---- 距离条 ---- */
.distance-bar {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.db-track {
  position: relative;
  height: 22px;
  background: var(--bg-inset);
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  overflow: hidden;
}

.db-ma {
  position: absolute;
  inset: 0 auto 0 0;
  background: var(--ma-band);
  border-right: 2px solid var(--status-ok);
}

.db-ma-end {
  position: absolute;
  top: 0;
  bottom: 0;
  width: 3px;
  background: var(--status-ok);
}

.db-stop-mark {
  position: absolute;
  top: 0;
  bottom: 0;
  width: 2px;
  background: var(--status-warn);
}

.db-station-mark {
  position: absolute;
  top: -2px;
  transform: translateX(-50%);
  color: var(--accent);
  font-size: 16px;
  line-height: 22px;
}

.db-legend {
  display: flex;
  gap: var(--gap-lg);
  flex-wrap: wrap;
  font-size: var(--fs-xs);
  color: var(--text-secondary);
}

.ma-reason {
  margin: 0;
  font-size: var(--fs-xs);
  color: var(--text-muted);
}

/* ---- 遥测 ---- */
.telemetry-row {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(90px, 1fr));
  gap: var(--gap-sm);
}

.tele {
  background: var(--bg-panel-raised);
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  padding: 6px 10px;
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.tele span {
  font-size: 10px;
  color: var(--text-muted);
}

.tele strong {
  font-size: var(--fs-sm);
}

/* ---- 状态灯矩阵 ---- */
.lamp-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(120px, 1fr));
  gap: var(--gap-sm);
}

.lamp {
  border-radius: var(--radius-md);
  padding: 8px 10px;
  border: 1px solid var(--border);
  background: var(--bg-panel-raised);
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.lamp-label {
  font-size: 10px;
  color: var(--text-muted);
}

.lamp-value {
  font-size: var(--fs-sm);
}

.lamp.tone-ok { border-color: var(--status-ok); }
.lamp.tone-ok .lamp-value { color: var(--status-ok); }
.lamp.tone-warn { border-color: var(--status-warn); background: var(--status-warn-bg); }
.lamp.tone-warn .lamp-value { color: var(--status-warn); }
.lamp.tone-danger { border-color: var(--status-danger); background: var(--status-danger-bg); }
.lamp.tone-danger .lamp-value { color: var(--status-danger); }
.lamp.tone-fault { border-color: var(--status-fault); background: var(--status-fault-bg); }
.lamp.tone-info { border-color: var(--status-info); }
.lamp.tone-info .lamp-value { color: var(--status-info); }
.lamp.tone-neutral .lamp-value { color: var(--text-secondary); }

.section-label {
  margin: var(--gap-md) 0 0;
  font-size: var(--fs-xs);
  color: var(--text-muted);
  letter-spacing: 0.06em;
}

.echo-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(110px, 1fr));
  gap: var(--gap-sm);
}

.echo-grid div {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.echo-grid span {
  font-size: 10px;
  color: var(--text-muted);
}

.hex-frame {
  display: flex;
  flex-wrap: wrap;
  gap: 3px;
  background: var(--bg-inset);
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  padding: 8px;
}

.hex-byte {
  font-size: 11px;
  padding: 1px 3px;
  border-radius: 3px;
}

.hex-byte.header {
  color: var(--text-muted);
}

.hex-byte.data {
  color: var(--status-ok);
  background: var(--status-ok-bg);
  font-weight: 700;
}

/* ---- 司控台 ---- */
.console {
  display: flex;
  flex-direction: column;
  gap: var(--gap-md);
}

.console-row {
  display: flex;
  align-items: center;
  gap: var(--gap-sm);
  flex-wrap: wrap;
}

.segmented {
  display: inline-flex;
  border: 1px solid var(--border-strong);
  border-radius: var(--radius-md);
  overflow: hidden;
}

.segmented button {
  border: none;
  background: var(--bg-panel-raised);
  color: var(--text-secondary);
  font-size: var(--fs-xs);
  font-weight: 600;
  padding: 6px 12px;
  cursor: pointer;
}

.segmented button + button {
  border-left: 1px solid var(--border);
}

.segmented button.active {
  background: var(--accent-muted);
  color: var(--accent);
}

.cab-key,
.cab-btn {
  border: 1px solid var(--border-strong);
  background: var(--bg-panel-raised);
  color: var(--text-primary);
  border-radius: var(--radius-md);
  padding: 6px 12px;
  font-size: var(--fs-sm);
  font-weight: 600;
  cursor: pointer;
}

.cab-key.on,
.cab-btn.on {
  border-color: var(--status-ok);
  background: var(--status-ok-bg);
  color: var(--status-ok);
}

.handle-block {
  background: var(--bg-panel-raised);
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  padding: 10px 12px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.handle-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.handle-head > span {
  font-size: var(--fs-sm);
  color: var(--text-secondary);
  font-weight: 600;
}

.handle-scale {
  display: flex;
  align-items: center;
  gap: 8px;
}

.handle-scale input[type='range'] {
  flex: 1;
  accent-color: var(--accent);
}

.hs-label {
  font-size: 10px;
  white-space: nowrap;
}

.hs-label.brake { color: var(--status-warn); }
.hs-label.traction { color: var(--status-ok); }

.handle-actions {
  display: flex;
  gap: var(--gap-sm);
}

.eb-mushroom {
  border: 3px solid var(--status-danger);
  background: var(--status-danger-bg);
  color: var(--status-danger);
  font-weight: 800;
  font-size: var(--fs-md);
  border-radius: 999px;
  padding: 12px;
  cursor: pointer;
  letter-spacing: 0.1em;
}

.eb-mushroom.pressed {
  background: var(--status-danger);
  color: #fff;
  box-shadow: 0 0 18px rgba(240, 86, 79, 0.6);
}

.send-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--gap-md);
}

.autosend {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: var(--fs-xs);
  color: var(--text-secondary);
  cursor: pointer;
}

.acceptance {
  display: flex;
  align-items: center;
  gap: var(--gap-sm);
  flex-wrap: wrap;
  padding: 8px 10px;
  border-radius: var(--radius-md);
  border: 1px solid var(--border);
  background: var(--bg-panel-raised);
}

.acceptance .small {
  font-size: 10px;
  color: var(--text-secondary);
  word-break: break-all;
}

.send-error {
  margin: 0;
  color: var(--status-danger);
  font-size: var(--fs-xs);
}

.console-hint {
  margin: 0;
  font-size: var(--fs-xs);
  color: var(--text-muted);
}

.muted-text {
  color: var(--text-muted);
  font-size: var(--fs-sm);
}
</style>
