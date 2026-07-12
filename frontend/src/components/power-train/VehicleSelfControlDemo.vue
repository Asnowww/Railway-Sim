<script setup lang="ts">
import { computed, onMounted, onUnmounted, reactive, ref } from 'vue'
import { simulationApi } from '../../api/rest'
import { simulationSocket } from '../../api/ws'
import type { PowerSectionState, SimulationSnapshot, TrackSegmentState, TrainState } from '../../types/simulation'

type DynamicsState =
  | 'SELF_CHECK_BLOCKED'
  | 'SAFETY_BRAKE'
  | 'SIGNAL_HOLD'
  | 'POWER_LOSS'
  | 'MA_BRAKE'
  | 'STATION_STOPPED'
  | 'STATION_BRAKE'
  | 'OVERSPEED_BRAKE'
  | 'POWER_DERATED'
  | 'OVERLOAD_DERATED'
  | 'ACCELERATING'
  | 'CRUISING'
  | 'COASTING'

type ScenarioId = 'departure' | 'station' | 'overspeed' | 'power' | 'authority' | 'door'

interface DynamicsDecision {
  state: DynamicsState
  reason: string
  tractionCommand: number
  brakeCommand: number
  emergencyBrake: boolean
  stoppingDistanceMeters: number
  movementAuthorityDistanceMeters: number
  stationDistanceMeters: number
  speedLimitMetersPerSecond: number
}

interface DemoConfig {
  scenario: ScenarioId
  autoRun: boolean
  lineLengthMeters: number
  speedLimitKmh: number
  movementAuthorityEndMeters: number
  stationPositionMeters: number
  gradientPermille: number
  powerAvailableMw: number
  powerDeratingFactor: number
  railVoltage: number
  doorClosed: boolean
  currentCollectionAvailable: boolean
  tractionAvailable: boolean
  brakeAvailable: boolean
  selfCheckPass: boolean
  signalHold: boolean
}

interface DemoTrainState {
  id: string
  positionMeters: number
  speedMetersPerSecond: number
  accelerationMetersPerSecondSquared: number
  tractionCommand: number
  brakeCommand: number
  emergencyBrake: boolean
  tractionForceNewtons: number
  brakeForceNewtons: number
  regenBrakeForceNewtons: number
  tractionPowerWatts: number
  regenPowerWatts: number
  energyConsumedKwh: number
  energyRegeneratedKwh: number
  dynamicsState: DynamicsState
  dynamicsConstraintReason: string
  speedLimitMetersPerSecond: number
  movementAuthorityDistanceMeters: number
  stationDistanceMeters: number
  stoppingDistanceMeters: number
}

interface HistoryPoint {
  speedMetersPerSecond: number
  speedLimitMetersPerSecond: number
  positionMeters: number
  dynamicsState: DynamicsState
}

const SERVICE_BRAKE_DECELERATION = 0.9
const GRAVITY = 9.81
const SAFETY_GAP_METERS = 120
const STATION_STOP_WINDOW_METERS = 8
const EMPTY_MASS_KG = 198_000
const MAX_LOAD_MASS_KG = 72_000
const LOAD_RATE = 0.52
const DEMO_STEP_SECONDS = 0.2

const stateDefinitions: Array<{ state: DynamicsState; label: string; tone: string }> = [
  { state: 'SELF_CHECK_BLOCKED', label: '自检/联锁阻断', tone: 'danger' },
  { state: 'SAFETY_BRAKE', label: '移动授权耗尽', tone: 'danger' },
  { state: 'SIGNAL_HOLD', label: '信号保持', tone: 'warning' },
  { state: 'POWER_LOSS', label: '受流/供电丢失', tone: 'danger' },
  { state: 'MA_BRAKE', label: 'MA 约束制动', tone: 'warning' },
  { state: 'STATION_STOPPED', label: '站台停车保持', tone: 'neutral' },
  { state: 'STATION_BRAKE', label: '进站制动', tone: 'warning' },
  { state: 'OVERSPEED_BRAKE', label: '超速制动', tone: 'warning' },
  { state: 'POWER_DERATED', label: '供电降额牵引', tone: 'info' },
  { state: 'OVERLOAD_DERATED', label: '超载降额牵引', tone: 'warning' },
  { state: 'ACCELERATING', label: '牵引加速', tone: 'success' },
  { state: 'CRUISING', label: '巡航控速', tone: 'success' },
  { state: 'COASTING', label: '惰行', tone: 'neutral' }
]

const reasonLabels: Record<string, string> = {
  DOOR_NOT_LOCKED: '车门未锁闭',
  SELF_CHECK_FAILED: '自检失败',
  BRAKE_UNAVAILABLE: '制动不可用',
  MOVEMENT_AUTHORITY_EXHAUSTED: '移动授权耗尽',
  SIGNAL_HOLD: '信号保持',
  CURRENT_COLLECTION_LOST: '受流中断',
  POWER_NOT_AVAILABLE: '供电不可用',
  TRACTION_UNAVAILABLE: '牵引不可用',
  MA_DISTANCE_LIMIT: '接近移动授权边界',
  STATION_STOP_WINDOW: '位于站台停车窗口',
  STATION_APPROACH: '接近下一站',
  SPEED_LIMIT_EXCEEDED: '超过目标限速',
  POWER_DERATING: '供电能力降额',
  OVERLOAD_TRACTION_LIMIT: '超载牵引限制',
  TRACTION_UNIT_DERATED: '牵引单元降额',
  SPEED_MARGIN_AVAILABLE: '存在速度裕量',
  NEAR_TARGET_SPEED: '接近目标速度',
  TARGET_SPEED_REACHED: '达到目标速度'
}

const scenarioDefinitions: Array<{
  id: ScenarioId
  label: string
  config: Partial<DemoConfig>
  initial: Partial<DemoTrainState>
}> = [
  {
    id: 'departure',
    label: '区间发车',
    config: {
      speedLimitKmh: 72,
      movementAuthorityEndMeters: 1_850,
      stationPositionMeters: 1_080,
      powerAvailableMw: 3.2,
      powerDeratingFactor: 1
    },
    initial: { positionMeters: 60, speedMetersPerSecond: 0 }
  },
  {
    id: 'station',
    label: '进站停车',
    config: {
      speedLimitKmh: 62,
      movementAuthorityEndMeters: 1_240,
      stationPositionMeters: 865
    },
    initial: { positionMeters: 590, speedMetersPerSecond: 17.5 }
  },
  {
    id: 'overspeed',
    label: '限速收敛',
    config: {
      speedLimitKmh: 42,
      movementAuthorityEndMeters: 1_620,
      stationPositionMeters: 1_380
    },
    initial: { positionMeters: 350, speedMetersPerSecond: 18.5 }
  },
  {
    id: 'power',
    label: '供电降额',
    config: {
      speedLimitKmh: 72,
      movementAuthorityEndMeters: 1_780,
      stationPositionMeters: 1_420,
      powerAvailableMw: 1.5,
      powerDeratingFactor: 0.48
    },
    initial: { positionMeters: 260, speedMetersPerSecond: 9.5 }
  },
  {
    id: 'authority',
    label: 'MA 边界',
    config: {
      speedLimitKmh: 68,
      movementAuthorityEndMeters: 725,
      stationPositionMeters: 1_600
    },
    initial: { positionMeters: 540, speedMetersPerSecond: 16.2 }
  },
  {
    id: 'door',
    label: '车门联锁',
    config: {
      speedLimitKmh: 45,
      movementAuthorityEndMeters: 980,
      stationPositionMeters: 840,
      doorClosed: false
    },
    initial: { positionMeters: 118, speedMetersPerSecond: 2.8 }
  }
]

function baseConfig(): DemoConfig {
  return {
    scenario: 'departure',
    autoRun: true,
    lineLengthMeters: 2_000,
    speedLimitKmh: 72,
    movementAuthorityEndMeters: 1_850,
    stationPositionMeters: 1_080,
    gradientPermille: 0,
    powerAvailableMw: 3.2,
    powerDeratingFactor: 1,
    railVoltage: 1500,
    doorClosed: true,
    currentCollectionAvailable: true,
    tractionAvailable: true,
    brakeAvailable: true,
    selfCheckPass: true,
    signalHold: false
  }
}

function baseTrainState(): DemoTrainState {
  return {
    id: 'TR-DEMO-01',
    positionMeters: 60,
    speedMetersPerSecond: 0,
    accelerationMetersPerSecondSquared: 0,
    tractionCommand: 0,
    brakeCommand: 0,
    emergencyBrake: false,
    tractionForceNewtons: 0,
    brakeForceNewtons: 0,
    regenBrakeForceNewtons: 0,
    tractionPowerWatts: 0,
    regenPowerWatts: 0,
    energyConsumedKwh: 0,
    energyRegeneratedKwh: 0,
    dynamicsState: 'COASTING',
    dynamicsConstraintReason: 'TARGET_SPEED_REACHED',
    speedLimitMetersPerSecond: 20,
    movementAuthorityDistanceMeters: 1_790,
    stationDistanceMeters: 1_020,
    stoppingDistanceMeters: 0
  }
}

const snapshot = ref<SimulationSnapshot | null>(null)
const backendState = ref<'waiting' | 'live' | 'offline'>('waiting')
const backendMessage = ref('等待后端快照')
const commandPending = ref<string | null>(null)
const localConfig = reactive<DemoConfig>(baseConfig())
const demoTrain = reactive<DemoTrainState>(baseTrainState())
const history = ref<HistoryPoint[]>([])

// Driver cab state for PLC input
const driverCabInput = reactive({
  selectedTrainId: 'TR-001',
  keySwitchLocked: false,
  directionHandleState: 'FORWARD' as 'FORWARD' | 'ZERO' | 'BACKWARD',
  masterHandleState: 'ZERO' as 'TRACTION' | 'ZERO' | 'BRAKE' | 'FAST_BRAKE',
  tractionNotchPercent: 0,
  brakeNotchPercent: 0,
  emergencyBrakeButtonLocked: false,
  doorsClosedLockedIndicator: true,
  atoModeActive: false,
  atoStartFlag: false,
  doorModeSwitchState: 'SEMI_AUTOMATIC' as 'AUTOMATIC' | 'SEMI_AUTOMATIC' | 'MANUAL',
  sendPending: false,
  sendResult: ''
})

const trainIds = computed(() => snapshot.value?.trains.map(t => t.id) ?? [])
const selectedLiveTrain = computed(() =>
  snapshot.value?.trains.find(t => t.id === driverCabInput.selectedTrainId) ?? null
)

let unsubscribeSocket: (() => void) | undefined
let snapshotPollTimer: number | undefined
let demoTimer: number | undefined

const liveTrain = computed<TrainState | null>(() => snapshot.value?.trains[0] ?? null)
const liveTrackSegments = computed<TrackSegmentState[]>(() => {
  if (snapshot.value?.trackSegments.length) {
    return snapshot.value.trackSegments
  }
  return [
    {
      id: 'LOCAL-DEMO',
      startMeters: 0,
      endMeters: localConfig.lineLengthMeters,
      speedLimitMetersPerSecond: localConfig.speedLimitKmh / 3.6,
      occupancy: 'FREE',
      fromNode: 'LOCAL-A',
      toNode: 'LOCAL-B',
      track: 'UP'
    }
  ]
})
const livePowerSections = computed<PowerSectionState[]>(() => snapshot.value?.powerSections ?? [])
const powerDiagramLength = computed(() => {
  const trackEnd = liveTrackSegments.value.reduce((max, segment) => Math.max(max, segment.endMeters), 0)
  const sectionEnd = livePowerSections.value.reduce((max, section) => Math.max(max, section.endMeters), 0)
  return Math.max(localConfig.lineLengthMeters, trackEnd, sectionEnd, 1)
})
const powerTrainPosition = computed(() => liveTrain.value?.positionMeters ?? demoTrain.positionMeters)
const powerTrainPower = computed(() => liveTrain.value?.tractionPowerWatts ?? demoTrain.tractionPowerWatts)
const powerTrainCurrent = computed(() => {
  if (liveTrain.value) {
    return liveTrain.value.railCurrentAmps
  }
  return localConfig.railVoltage > 1 ? demoTrain.tractionPowerWatts / localConfig.railVoltage : 0
})
const powerQualitySummary = computed(() => {
  const qualities = [...new Set(livePowerSections.value.map((section) => section.externalDataQuality || section.dataQuality))]
  return qualities.length > 0 ? qualities.join(' / ') : 'NO_DATA'
})
const powerComparisonSummary = computed(() => {
  if (livePowerSections.value.length === 0) {
    return '等待供电快照'
  }
  if (livePowerSections.value.some((section) => section.voltageComparisonStatus === 'DIVERGED')) {
    return '外部电压显著偏离'
  }
  if (livePowerSections.value.some((section) => section.voltageComparisonStatus === 'DEVIATED')) {
    return '外部电压轻微偏离'
  }
  if (livePowerSections.value.some((section) => section.voltageComparisonStatus === 'MATCHED')) {
    return '中央/外部电压匹配'
  }
  return '无外部电压数据'
})
const backendStateLabel = computed(() => {
  if (backendState.value === 'live') {
    return `LIVE tick ${snapshot.value?.tick ?? 0}`
  }
  if (backendState.value === 'offline') {
    return '后端未连接'
  }
  return '等待快照'
})
const activeStateMeta = computed(() => stateDefinitions.find((item) => item.state === demoTrain.dynamicsState))
const reasonText = computed(() => reasonLabels[demoTrain.dynamicsConstraintReason] ?? demoTrain.dynamicsConstraintReason)
const speedKmh = computed(() => demoTrain.speedMetersPerSecond * 3.6)
const speedLimitKmh = computed(() => demoTrain.speedLimitMetersPerSecond * 3.6)
const trainProgress = computed(() => percent(demoTrain.positionMeters / localConfig.lineLengthMeters))
const stationProgress = computed(() => percent(localConfig.stationPositionMeters / localConfig.lineLengthMeters))
const authorityProgress = computed(() => percent(localConfig.movementAuthorityEndMeters / localConfig.lineLengthMeters))
const chartMaxSpeed = computed(() => {
  const observed = history.value.reduce(
    (max, point) => Math.max(max, point.speedMetersPerSecond, point.speedLimitMetersPerSecond),
    localConfig.speedLimitKmh / 3.6
  )
  return Math.max(observed, 1)
})
const speedPolyline = computed(() => buildPolyline((point) => point.speedMetersPerSecond))
const limitPolyline = computed(() => buildPolyline((point) => point.speedLimitMetersPerSecond))
const constraintHealth = computed(() => [
  { label: '车门锁闭', active: localConfig.doorClosed },
  { label: '受流正常', active: localConfig.currentCollectionAvailable },
  { label: '牵引可用', active: localConfig.tractionAvailable },
  { label: '制动可用', active: localConfig.brakeAvailable },
  { label: '自检通过', active: localConfig.selfCheckPass },
  { label: '信号放行', active: !localConfig.signalHold }
])

onMounted(() => {
  applyScenario('departure')
  fetchSnapshot()
  unsubscribeSocket = simulationSocket.subscribe((nextSnapshot) => {
    acceptSnapshot(nextSnapshot, 'WebSocket')
  })
  simulationSocket.connect()
  snapshotPollTimer = window.setInterval(fetchSnapshot, 3_000)
  demoTimer = window.setInterval(() => {
    if (localConfig.autoRun) {
      stepDemo()
    }
  }, DEMO_STEP_SECONDS * 1000)
})

onUnmounted(() => {
  unsubscribeSocket?.()
  simulationSocket.disconnect()
  if (snapshotPollTimer !== undefined) {
    window.clearInterval(snapshotPollTimer)
  }
  if (demoTimer !== undefined) {
    window.clearInterval(demoTimer)
  }
})

async function fetchSnapshot() {
  try {
    const nextSnapshot = await simulationApi.snapshot()
    acceptSnapshot(nextSnapshot, 'REST')
  } catch (error) {
    backendState.value = 'offline'
    backendMessage.value = parseError(error)
  }
}

async function runSimulationCommand(command: 'start' | 'pause' | 'reset') {
  commandPending.value = command
  try {
    const nextSnapshot = await simulationApi[command]()
    acceptSnapshot(nextSnapshot, 'REST')
  } catch (error) {
    backendState.value = 'offline'
    backendMessage.value = parseError(error)
  } finally {
    commandPending.value = null
  }
}

function acceptSnapshot(nextSnapshot: SimulationSnapshot, source: string) {
  snapshot.value = nextSnapshot
  backendState.value = 'live'
  backendMessage.value = `${source} ${new Date().toLocaleTimeString()}`
}

async function sendPlcInput() {
  const trainId = driverCabInput.selectedTrainId
  if (!trainId) return
  driverCabInput.sendPending = true
  driverCabInput.sendResult = ''
  try {
    const body = {
      highVoltageClosedIndicator: true,
      doorsClosedLockedIndicator: driverCabInput.doorsClosedLockedIndicator,
      networkFaultIndicator: false,
      automaticTurnbackAvailable: false,
      atoModeAvailable: true,
      atoModeActive: driverCabInput.atoModeActive,
      automaticTurnbackActive: false,
      emergencyBrakeButtonLocked: driverCabInput.emergencyBrakeButtonLocked,
      openLeftDoorFlag: !driverCabInput.doorsClosedLockedIndicator,
      openRightDoorFlag: !driverCabInput.doorsClosedLockedIndicator,
      closeLeftDoorFlag: driverCabInput.doorsClosedLockedIndicator,
      closeRightDoorFlag: driverCabInput.doorsClosedLockedIndicator,
      doorModeSwitchState: driverCabInput.doorModeSwitchState,
      modeUpgradeConfirmFlag: false,
      modeDowngradeConfirmFlag: false,
      automaticTurnbackFlag: false,
      atoStartFlag: driverCabInput.atoStartFlag,
      keySwitchLocked: driverCabInput.keySwitchLocked,
      directionHandleState: driverCabInput.directionHandleState,
      masterHandleState: driverCabInput.masterHandleState,
      tractionNotchPercent: driverCabInput.tractionNotchPercent,
      brakeNotchPercent: driverCabInput.brakeNotchPercent
    }
    const resp = await fetch(`/api/vehicle/driver-cabs/${trainId}/plc-input`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body)
    })
    if (resp.ok) {
      const result = await resp.json()
      driverCabInput.sendResult = result.accepted ? '✅ 已接受' : '❌ 拒绝: ' + (result.reasonCode || 'UNKNOWN')
    } else {
      driverCabInput.sendResult = `❌ HTTP ${resp.status}`
    }
  } catch (e: any) {
    driverCabInput.sendResult = '❌ ' + (e.message || '网络错误')
  } finally {
    driverCabInput.atoStartFlag = false
    driverCabInput.sendPending = false
  }
}

function updateMasterHandle(value: number) {
  if (value > 0) {
    driverCabInput.masterHandleState = 'TRACTION'
    driverCabInput.tractionNotchPercent = value
    driverCabInput.brakeNotchPercent = 0
  } else if (value < 0) {
    driverCabInput.masterHandleState = value <= -51 ? 'FAST_BRAKE' : 'BRAKE'
    driverCabInput.brakeNotchPercent = Math.abs(value)
    driverCabInput.tractionNotchPercent = 0
  } else {
    driverCabInput.masterHandleState = 'ZERO'
    driverCabInput.tractionNotchPercent = 0
    driverCabInput.brakeNotchPercent = 0
  }
}

function cycleDirection() {
  const order: Array<'FORWARD' | 'ZERO' | 'BACKWARD'> = ['FORWARD', 'ZERO', 'BACKWARD']
  const idx = order.indexOf(driverCabInput.directionHandleState)
  driverCabInput.directionHandleState = order[(idx + 1) % 3]
}

function atoStart() {
  driverCabInput.atoStartFlag = true
  driverCabInput.atoModeActive = true
  setTimeout(() => sendPlcInput(), 100)
}

function cycleDoorMode() {
  const order: Array<'AUTOMATIC' | 'SEMI_AUTOMATIC' | 'MANUAL'> = ['SEMI_AUTOMATIC', 'AUTOMATIC', 'MANUAL']
  const idx = order.indexOf(driverCabInput.doorModeSwitchState)
  driverCabInput.doorModeSwitchState = order[(idx + 1) % 3]
}

function applyScenario(id: ScenarioId) {
  const scenario = scenarioDefinitions.find((item) => item.id === id) ?? scenarioDefinitions[0]
  Object.assign(localConfig, baseConfig(), scenario.config, { scenario: id })
  Object.assign(demoTrain, baseTrainState(), scenario.initial)
  history.value = []
  for (let index = 0; index < 8; index += 1) {
    stepDemo()
  }
}

function resetDemo() {
  applyScenario(localConfig.scenario)
}

function stepDemo() {
  const decision = decideLocalDynamics()
  const massKg = EMPTY_MASS_KG + MAX_LOAD_MASS_KG * LOAD_RATE
  const tractionForce = decision.tractionCommand * 220_000
  const brakeForce = decision.brakeCommand * (decision.emergencyBrake ? 300_000 : 235_000)
  const regenBrakeForce =
    decision.brakeCommand > 0 && demoTrain.speedMetersPerSecond > 1 && localConfig.currentCollectionAvailable
      ? Math.min(brakeForce * 0.58, 125_000)
      : 0
  const rollingResistance = 3_800 + demoTrain.speedMetersPerSecond * 260
  const gradientForce = massKg * GRAVITY * gradientRatio()
  const acceleration = clamp(
    (tractionForce - brakeForce - rollingResistance - gradientForce) / massKg,
    -1.35,
    1.05
  )
  const previousSpeed = demoTrain.speedMetersPerSecond
  const nextSpeed = Math.max(0, previousSpeed + acceleration * DEMO_STEP_SECONDS)
  const averageSpeed = (previousSpeed + nextSpeed) / 2
  const nextPosition = clamp(
    demoTrain.positionMeters + averageSpeed * DEMO_STEP_SECONDS,
    0,
    localConfig.lineLengthMeters
  )
  const tractionPower = tractionForce * Math.max(nextSpeed, 0)
  const regenPower = regenBrakeForce * Math.max(nextSpeed, 0)

  demoTrain.positionMeters = nextPosition
  demoTrain.speedMetersPerSecond = nextSpeed
  demoTrain.accelerationMetersPerSecondSquared = nextSpeed <= 0.01 && acceleration < 0 ? 0 : acceleration
  demoTrain.tractionCommand = decision.tractionCommand
  demoTrain.brakeCommand = decision.brakeCommand
  demoTrain.emergencyBrake = decision.emergencyBrake
  demoTrain.tractionForceNewtons = tractionForce
  demoTrain.brakeForceNewtons = brakeForce
  demoTrain.regenBrakeForceNewtons = regenBrakeForce
  demoTrain.tractionPowerWatts = tractionPower
  demoTrain.regenPowerWatts = regenPower
  demoTrain.energyConsumedKwh += (tractionPower * DEMO_STEP_SECONDS) / 3_600_000
  demoTrain.energyRegeneratedKwh += (regenPower * DEMO_STEP_SECONDS) / 3_600_000
  demoTrain.dynamicsState = decision.state
  demoTrain.dynamicsConstraintReason = decision.reason
  demoTrain.speedLimitMetersPerSecond = decision.speedLimitMetersPerSecond
  demoTrain.movementAuthorityDistanceMeters = decision.movementAuthorityDistanceMeters
  demoTrain.stationDistanceMeters = decision.stationDistanceMeters
  demoTrain.stoppingDistanceMeters = decision.stoppingDistanceMeters

  history.value = [
    ...history.value.slice(-119),
    {
      speedMetersPerSecond: demoTrain.speedMetersPerSecond,
      speedLimitMetersPerSecond: demoTrain.speedLimitMetersPerSecond,
      positionMeters: demoTrain.positionMeters,
      dynamicsState: demoTrain.dynamicsState
    }
  ]
}

function decideLocalDynamics(): DynamicsDecision {
  const speed = demoTrain.speedMetersPerSecond
  const speedLimit = Math.max(0, localConfig.speedLimitKmh / 3.6)
  const maDistance = Math.max(0, localConfig.movementAuthorityEndMeters - demoTrain.positionMeters)
  const stationDistance = Math.max(0, localConfig.stationPositionMeters - demoTrain.positionMeters)
  const stoppingDistance = stoppingDistanceMeters(speed)

  if (!localConfig.doorClosed || !localConfig.brakeAvailable || !localConfig.selfCheckPass) {
    const reason = !localConfig.doorClosed
      ? 'DOOR_NOT_LOCKED'
      : !localConfig.brakeAvailable
        ? 'BRAKE_UNAVAILABLE'
        : 'SELF_CHECK_FAILED'
    return brakeDecision('SELF_CHECK_BLOCKED', reason, speed, stoppingDistance, maDistance, stationDistance, false)
  }
  if (maDistance <= 0) {
    return brakeDecision(
      'SAFETY_BRAKE',
      'MOVEMENT_AUTHORITY_EXHAUSTED',
      speed,
      stoppingDistance,
      maDistance,
      stationDistance,
      true
    )
  }
  if (localConfig.signalHold) {
    return decision('SIGNAL_HOLD', 'SIGNAL_HOLD', 0, speed > 0.1 ? 1 : 0.6, false, stoppingDistance)
  }
  if (
    !localConfig.currentCollectionAvailable ||
    localConfig.powerAvailableMw <= 0 ||
    localConfig.railVoltage <= 0
  ) {
    return brakeDecision(
      'POWER_LOSS',
      !localConfig.currentCollectionAvailable ? 'CURRENT_COLLECTION_LOST' : 'POWER_NOT_AVAILABLE',
      speed,
      stoppingDistance,
      maDistance,
      stationDistance,
      false
    )
  }
  if (!localConfig.tractionAvailable) {
    return decision(
      'SELF_CHECK_BLOCKED',
      'TRACTION_UNAVAILABLE',
      0,
      speed > 0.1 ? 0.4 : 0,
      false,
      stoppingDistance
    )
  }

  const maBrakeTrigger = stoppingDistance + SAFETY_GAP_METERS * 0.5
  if (maDistance <= maBrakeTrigger) {
    return decision(
      'MA_BRAKE',
      'MA_DISTANCE_LIMIT',
      0,
      brakeForDistance(maDistance, stoppingDistance, SAFETY_GAP_METERS * 0.5),
      false,
      stoppingDistance
    )
  }
  if (stationDistance <= STATION_STOP_WINDOW_METERS && speed <= 0.2) {
    return decision('STATION_STOPPED', 'STATION_STOP_WINDOW', 0, 0.6, false, stoppingDistance)
  }

  const stationBrakeBuffer = stationApproachBufferMeters(speed)
  if (stationDistance <= stoppingDistance + stationBrakeBuffer) {
    return decision(
      'STATION_BRAKE',
      'STATION_APPROACH',
      0,
      brakeForDistance(stationDistance, stoppingDistance, stationBrakeBuffer),
      false,
      stoppingDistance
    )
  }

  const overspeed = speed - speedLimit
  if (overspeed > 0) {
    return decision('OVERSPEED_BRAKE', 'SPEED_LIMIT_EXCEEDED', 0, clamp(overspeed / 3, 0.2, 1), false, stoppingDistance)
  }

  const speedMargin = speedLimit - speed
  const tractionCommand = tractionForSpeedMargin(speedMargin, speedLimit)
  if (localConfig.powerDeratingFactor < 0.95 && tractionCommand > 0) {
    return decision(
      'POWER_DERATED',
      'POWER_DERATING',
      tractionCommand * clamp(localConfig.powerDeratingFactor, 0, 1),
      0,
      false,
      stoppingDistance
    )
  }
  if (speedMargin > Math.max(1.5, speedLimit * 0.08)) {
    return decision('ACCELERATING', 'SPEED_MARGIN_AVAILABLE', tractionCommand, 0, false, stoppingDistance)
  }
  if (speedMargin > 0.4) {
    return decision('CRUISING', 'NEAR_TARGET_SPEED', Math.min(tractionCommand, 0.25), 0, false, stoppingDistance)
  }
  return decision('COASTING', 'TARGET_SPEED_REACHED', 0, 0, false, stoppingDistance)
}

function decision(
  state: DynamicsState,
  reason: string,
  tractionCommand: number,
  brakeCommand: number,
  emergencyBrake: boolean,
  stoppingDistance: number
): DynamicsDecision {
  return {
    state,
    reason,
    tractionCommand,
    brakeCommand,
    emergencyBrake,
    stoppingDistanceMeters: stoppingDistance,
    movementAuthorityDistanceMeters: Math.max(0, localConfig.movementAuthorityEndMeters - demoTrain.positionMeters),
    stationDistanceMeters: Math.max(0, localConfig.stationPositionMeters - demoTrain.positionMeters),
    speedLimitMetersPerSecond: Math.max(0, localConfig.speedLimitKmh / 3.6)
  }
}

function brakeDecision(
  state: DynamicsState,
  reason: string,
  speed: number,
  stoppingDistance: number,
  maDistance: number,
  stationDistance: number,
  emergencyBrake: boolean
): DynamicsDecision {
  return {
    state,
    reason,
    tractionCommand: 0,
    brakeCommand: emergencyBrake ? 1 : speed > 0.1 ? 0.8 : 0.6,
    emergencyBrake,
    stoppingDistanceMeters: stoppingDistance,
    movementAuthorityDistanceMeters: maDistance,
    stationDistanceMeters: stationDistance,
    speedLimitMetersPerSecond: Math.max(0, localConfig.speedLimitKmh / 3.6)
  }
}

function stoppingDistanceMeters(speedMetersPerSecond: number) {
  const planningGradient = clamp(gradientRatio(), -0.04, 0.04)
  const effectiveDeceleration = clamp(SERVICE_BRAKE_DECELERATION + planningGradient * GRAVITY, 0.35, 1.25)
  return (speedMetersPerSecond * speedMetersPerSecond) / (2 * effectiveDeceleration)
}

function stationApproachBufferMeters(speedMetersPerSecond: number) {
  return clamp(Math.max(30, speedMetersPerSecond * 6), 30, 140)
}

function brakeForDistance(remainingDistance: number, stoppingDistance: number, bufferMeters: number) {
  const shortfall = stoppingDistance + bufferMeters - remainingDistance
  return clamp(shortfall / Math.max(bufferMeters, 1), 0.2, 1)
}

function tractionForSpeedMargin(speedMargin: number, speedLimit: number) {
  if (speedMargin <= 0.4) {
    return 0
  }
  return clamp(speedMargin / Math.max(3, speedLimit * 0.25), 0, 1)
}

function gradientRatio() {
  return localConfig.gradientPermille / 1000
}

function buildPolyline(accessor: (point: HistoryPoint) => number) {
  if (history.value.length === 0) {
    return ''
  }
  const width = 640
  const height = 160
  const maxSpeed = chartMaxSpeed.value
  return history.value
    .map((point, index) => {
      const x = history.value.length === 1 ? 0 : (index / (history.value.length - 1)) * width
      const y = height - (accessor(point) / maxSpeed) * (height - 18) - 9
      return `${x.toFixed(1)},${clamp(y, 8, height - 8).toFixed(1)}`
    })
    .join(' ')
}

function mileageBandStyle(startMeters: number, endMeters: number) {
  const length = powerDiagramLength.value
  const start = clamp(startMeters / length, 0, 1)
  const end = clamp(endMeters / length, start, 1)
  return {
    left: `${(start * 100).toFixed(2)}%`,
    width: `${Math.max((end - start) * 100, 0.65).toFixed(2)}%`
  }
}

function powerTrainMarkerStyle() {
  return {
    left: percent(powerTrainPosition.value / powerDiagramLength.value)
  }
}

function voltageBarStyle(value: number, section: PowerSectionState) {
  const upper = Math.max(1, section.voltage, section.externalVoltage, localConfig.railVoltage)
  return {
    width: percent(value / upper)
  }
}

function powerSectionClass(section: PowerSectionState) {
  return [
    `status-${(section.status || 'UNKNOWN').toLowerCase().replace(/_/g, '-')}`,
    `comparison-${comparisonKey(section.voltageComparisonStatus)}`
  ]
}

function comparisonKey(status: string | undefined) {
  return (status || 'NO_EXTERNAL_DATA').toLowerCase().replace(/_/g, '-')
}

function translatePowerStatus(status: string | undefined) {
  const labels: Record<string, string> = {
    ENERGIZED: '带电',
    UNDERVOLTAGE: '欠压',
    OVERCURRENT: '过流',
    DEENERGIZED: '失电',
    ISOLATED: '隔离',
    TRIPPED: '跳闸',
    MAINTENANCE_LOCKED: '检修闭锁'
  }
  return labels[status ?? ''] ?? (status || '未知')
}

function translateComparisonStatus(status: string | undefined) {
  const labels: Record<string, string> = {
    MATCHED: '匹配',
    DEVIATED: '轻微偏离',
    DIVERGED: '显著偏离',
    NO_EXTERNAL_DATA: '无外部数据'
  }
  return labels[status ?? ''] ?? (status || '未知')
}

function translateSupplyMode(mode: string | undefined) {
  const labels: Record<string, string> = {
    DOUBLE_END: '双端供电',
    SINGLE_END: '单端供电',
    CROSS_FEED: '越区供电',
    OUTAGE: '停电'
  }
  return labels[mode ?? ''] ?? (mode || '未知')
}

function affectedTrainText(section: PowerSectionState) {
  return section.affectedTrainIds?.length > 0 ? section.affectedTrainIds.join('、') : '无'
}

function percent(value: number) {
  return `${clamp(value * 100, 0, 100).toFixed(2)}%`
}

function clamp(value: number, min: number, max: number) {
  return Math.max(min, Math.min(max, value))
}

function parseError(error: unknown) {
  return error instanceof Error ? error.message : '请求失败'
}

function formatSpeed(value: number) {
  return `${(value * 3.6).toFixed(1)} km/h`
}

function formatDistance(value: number) {
  if (value >= 1_000) {
    return `${(value / 1_000).toFixed(2)} km`
  }
  return `${value.toFixed(1)} m`
}

function formatPower(value: number) {
  return `${(value / 1_000_000).toFixed(2)} MW`
}

function formatVoltage(value: number) {
  return `${value.toFixed(1)} V`
}

function formatExternalVoltage(section: PowerSectionState) {
  if (section.voltageComparisonStatus === 'NO_EXTERNAL_DATA' || section.externalVoltage <= 0) {
    return '无外部数据'
  }
  return formatVoltage(section.externalVoltage)
}

function formatCurrent(value: number) {
  return `${value.toFixed(0)} A`
}

function formatSignedVoltage(value: number) {
  const sign = value > 0 ? '+' : ''
  return `${sign}${value.toFixed(1)} V`
}

function formatDeviationPercent(value: number) {
  return `${value.toFixed(2)}%`
}

function formatForce(value: number) {
  return `${(value / 1_000).toFixed(0)} kN`
}

function formatCommand(value: number) {
  return `${Math.round(value * 100)}%`
}

function stateLabel(state: string) {
  return stateDefinitions.find((item) => item.state === state)?.label ?? state
}
</script>

<template>
  <main class="app-shell">
    <header class="topbar">
      <div>
        <p class="eyebrow">Railway-Sim / Vehicle Control</p>
        <h1>车辆自身控制仿真展示</h1>
      </div>
      <div class="topbar-actions">
        <span class="connection-pill" :class="backendState">{{ backendStateLabel }}</span>
        <button type="button" :disabled="commandPending === 'start'" @click="runSimulationCommand('start')">
          启动
        </button>
        <button type="button" :disabled="commandPending === 'pause'" @click="runSimulationCommand('pause')">
          暂停
        </button>
        <button type="button" :disabled="commandPending === 'reset'" @click="runSimulationCommand('reset')">
          复位
        </button>
      </div>
    </header>

    <section class="live-strip" aria-label="后端仿真快照">
      <div class="live-copy">
        <span>后端快照</span>
        <strong>{{ backendMessage }}</strong>
      </div>
      <div class="live-metrics">
        <div>
          <span>仿真状态</span>
          <strong>{{ snapshot?.status ?? 'LOCAL' }}</strong>
        </div>
        <div>
          <span>列车数</span>
          <strong>{{ snapshot?.trains.length ?? 0 }}</strong>
        </div>
        <div>
          <span>首车状态</span>
          <strong>{{ liveTrain ? stateLabel(liveTrain.dynamicsState) : '本地预演' }}</strong>
        </div>
        <div>
          <span>首车速度</span>
          <strong>{{ liveTrain ? formatSpeed(liveTrain.speedMetersPerSecond) : formatSpeed(demoTrain.speedMetersPerSecond) }}</strong>
        </div>
      </div>
    </section>

    <section class="power-linkage" aria-label="供电网络联动">
      <div class="section-heading compact">
        <div>
          <span>供电网络联动</span>
          <h2>轨道拓扑支撑的虚拟电网闭环</h2>
        </div>
        <div class="power-summary">
          <span class="quality-pill">{{ powerQualitySummary }}</span>
          <strong>{{ powerComparisonSummary }}</strong>
        </div>
      </div>

      <div class="power-diagram">
        <div class="diagram-axis">
          <span>0 m</span>
          <span>{{ formatDistance(powerDiagramLength) }}</span>
        </div>
        <div class="diagram-row">
          <span class="diagram-label">轨道区段</span>
          <div class="diagram-lane track-lane">
            <span
              v-for="segment in liveTrackSegments"
              :key="segment.id"
              class="track-segment-band"
              :class="segment.occupancy.toLowerCase()"
              :style="mileageBandStyle(segment.startMeters, segment.endMeters)"
            >
              <b>{{ segment.id }}</b>
              <small>{{ segment.fromNode }} → {{ segment.toNode }}</small>
            </span>
          </div>
        </div>
        <div class="diagram-row">
          <span class="diagram-label">供电分区</span>
          <div class="diagram-lane power-lane">
            <span
              v-for="section in livePowerSections"
              :key="section.id"
              class="power-section-band"
              :class="powerSectionClass(section)"
              :style="mileageBandStyle(section.startMeters, section.endMeters)"
            >
              <b>{{ section.name || section.id }}</b>
              <small>{{ translatePowerStatus(section.status) }} / {{ translateSupplyMode(section.supplyMode) }}</small>
            </span>
            <span class="power-train-marker" :style="powerTrainMarkerStyle()">
              <i></i>
              <b>{{ liveTrain?.id ?? demoTrain.id }}</b>
              <small>{{ formatPower(powerTrainPower) }} / {{ formatCurrent(powerTrainCurrent) }}</small>
            </span>
          </div>
        </div>
      </div>

      <div v-if="livePowerSections.length === 0" class="power-empty">
        等待中央系统快照中的供电分区数据。
      </div>
      <div v-else class="power-section-cards">
        <article
          v-for="section in livePowerSections"
          :key="section.id"
          class="power-section-card"
          :class="powerSectionClass(section)"
        >
          <header>
            <div>
              <span>{{ section.id }}</span>
              <h3>{{ section.name }}</h3>
            </div>
            <strong>{{ translateComparisonStatus(section.voltageComparisonStatus) }}</strong>
          </header>

          <div class="voltage-bars" aria-label="中央与外部电压对比">
            <div class="voltage-row central">
              <span>中央电压</span>
              <i><b :style="voltageBarStyle(section.voltage, section)"></b></i>
              <strong>{{ formatVoltage(section.voltage) }}</strong>
            </div>
            <div class="voltage-row external">
              <span>外部电压</span>
              <i><b :style="voltageBarStyle(section.externalVoltage, section)"></b></i>
              <strong>{{ formatExternalVoltage(section) }}</strong>
            </div>
          </div>

          <div class="power-detail-grid">
            <div>
              <span>偏差</span>
              <strong>{{ formatSignedVoltage(section.voltageDeviation) }}</strong>
              <small>{{ formatDeviationPercent(section.voltageDeviationPercent) }}</small>
            </div>
            <div>
              <span>中央负荷</span>
              <strong>{{ formatPower(section.loadWatts) }}</strong>
              <small>{{ formatCurrent(section.current) }}</small>
            </div>
            <div>
              <span>外部负荷</span>
              <strong>{{ formatPower(section.externalLoadWatts) }}</strong>
              <small>{{ formatCurrent(section.externalCurrent) }}</small>
            </div>
            <div>
              <span>数据质量</span>
              <strong>{{ section.externalDataQuality }}</strong>
              <small>{{ section.dataQuality }}</small>
            </div>
            <div>
              <span>受影响列车</span>
              <strong>{{ affectedTrainText(section) }}</strong>
              <small>{{ section.externalSupportReason || '无外部支撑说明' }}</small>
            </div>
            <div>
              <span>杂散风险</span>
              <strong>{{ section.strayCurrentRiskLevel }}</strong>
              <small>{{ section.strayCurrentRiskReason || '状态正常' }}</small>
            </div>
          </div>
        </article>
      </div>
    </section>

    <section class="simulator-grid">
      <div class="train-board">
        <div class="section-heading">
          <div>
            <span>TR-DEMO-01</span>
            <h2>{{ activeStateMeta?.label ?? demoTrain.dynamicsState }}</h2>
          </div>
          <span class="state-badge" :class="activeStateMeta?.tone">{{ reasonText }}</span>
        </div>

        <div class="track-map">
          <div class="track-labels">
            <span>0 m</span>
            <span>{{ formatDistance(localConfig.lineLengthMeters) }}</span>
          </div>
          <div class="rail">
            <span class="authority-band" :style="{ width: authorityProgress }"></span>
            <span class="marker station-marker" :style="{ left: stationProgress }">
              <i></i>
              <b>站台</b>
            </span>
            <span class="marker authority-marker" :style="{ left: authorityProgress }">
              <i></i>
              <b>MA</b>
            </span>
            <span class="train-marker" :style="{ left: trainProgress }">
              <i></i>
              <b>{{ formatSpeed(demoTrain.speedMetersPerSecond) }}</b>
            </span>
          </div>
        </div>

        <div class="speed-panel">
          <div class="speed-readout">
            <span>速度</span>
            <strong>{{ speedKmh.toFixed(1) }}</strong>
            <small>km/h</small>
          </div>
          <svg class="speed-chart" viewBox="0 0 640 160" role="img" aria-label="速度与限速曲线">
            <path d="M0 151H640" class="chart-axis" />
            <polyline :points="limitPolyline" class="limit-line" />
            <polyline :points="speedPolyline" class="speed-line" />
          </svg>
          <div class="chart-legend">
            <span><i class="legend-speed"></i>实际速度</span>
            <span><i class="legend-limit"></i>目标限速 {{ speedLimitKmh.toFixed(1) }} km/h</span>
          </div>
        </div>

        <div class="telemetry-grid">
          <div>
            <span>位置</span>
            <strong>{{ formatDistance(demoTrain.positionMeters) }}</strong>
          </div>
          <div>
            <span>加速度</span>
            <strong>{{ demoTrain.accelerationMetersPerSecondSquared.toFixed(2) }} m/s²</strong>
          </div>
          <div>
            <span>牵引指令</span>
            <strong>{{ formatCommand(demoTrain.tractionCommand) }}</strong>
          </div>
          <div>
            <span>制动指令</span>
            <strong>{{ formatCommand(demoTrain.brakeCommand) }}</strong>
          </div>
          <div>
            <span>牵引力</span>
            <strong>{{ formatForce(demoTrain.tractionForceNewtons) }}</strong>
          </div>
          <div>
            <span>制动力</span>
            <strong>{{ formatForce(demoTrain.brakeForceNewtons) }}</strong>
          </div>
          <div>
            <span>MA 剩余</span>
            <strong>{{ formatDistance(demoTrain.movementAuthorityDistanceMeters) }}</strong>
          </div>
          <div>
            <span>站距</span>
            <strong>{{ formatDistance(demoTrain.stationDistanceMeters) }}</strong>
          </div>
          <div>
            <span>停车距离</span>
            <strong>{{ formatDistance(demoTrain.stoppingDistanceMeters) }}</strong>
          </div>
          <div>
            <span>牵引功率</span>
            <strong>{{ formatPower(demoTrain.tractionPowerWatts) }}</strong>
          </div>
        </div>
      </div>

      <aside class="control-board">
        <div class="section-heading compact">
          <div>
            <span>车辆自控输入</span>
            <h2>工况与约束</h2>
          </div>
          <label class="run-switch">
            <input v-model="localConfig.autoRun" type="checkbox" />
            <span>自动</span>
          </label>
        </div>

        <div class="scenario-tabs" role="tablist" aria-label="工况">
          <button
            v-for="scenario in scenarioDefinitions"
            :key="scenario.id"
            type="button"
            :class="{ active: localConfig.scenario === scenario.id }"
            @click="applyScenario(scenario.id)"
          >
            {{ scenario.label }}
          </button>
        </div>

        <div class="state-chain" aria-label="动力学状态机">
          <span
            v-for="item in stateDefinitions"
            :key="item.state"
            class="state-node"
            :class="[item.tone, { active: demoTrain.dynamicsState === item.state }]"
          >
            {{ item.label }}
          </span>
        </div>

        <div class="constraint-grid">
          <label v-for="item in constraintHealth" :key="item.label" class="constraint-toggle">
            <input
              v-if="item.label === '车门锁闭'"
              v-model="localConfig.doorClosed"
              type="checkbox"
            />
            <input
              v-else-if="item.label === '受流正常'"
              v-model="localConfig.currentCollectionAvailable"
              type="checkbox"
            />
            <input
              v-else-if="item.label === '牵引可用'"
              v-model="localConfig.tractionAvailable"
              type="checkbox"
            />
            <input
              v-else-if="item.label === '制动可用'"
              v-model="localConfig.brakeAvailable"
              type="checkbox"
            />
            <input
              v-else-if="item.label === '自检通过'"
              v-model="localConfig.selfCheckPass"
              type="checkbox"
            />
            <input
              v-else
              :checked="!localConfig.signalHold"
              type="checkbox"
              @change="localConfig.signalHold = !($event.target as HTMLInputElement).checked"
            />
            <span>{{ item.label }}</span>
          </label>
        </div>

        <div class="slider-stack">
          <label>
            <span>限速 {{ localConfig.speedLimitKmh.toFixed(0) }} km/h</span>
            <input v-model.number="localConfig.speedLimitKmh" min="20" max="100" step="1" type="range" />
          </label>
          <label>
            <span>MA 终点 {{ formatDistance(localConfig.movementAuthorityEndMeters) }}</span>
            <input
              v-model.number="localConfig.movementAuthorityEndMeters"
              min="180"
              :max="localConfig.lineLengthMeters"
              step="10"
              type="range"
            />
          </label>
          <label>
            <span>下一站 {{ formatDistance(localConfig.stationPositionMeters) }}</span>
            <input
              v-model.number="localConfig.stationPositionMeters"
              min="120"
              :max="localConfig.lineLengthMeters"
              step="10"
              type="range"
            />
          </label>
          <label>
            <span>供电能力 {{ localConfig.powerAvailableMw.toFixed(1) }} MW</span>
            <input v-model.number="localConfig.powerAvailableMw" min="0" max="3.8" step="0.1" type="range" />
          </label>
          <label>
            <span>牵引降额 {{ formatCommand(localConfig.powerDeratingFactor) }}</span>
            <input v-model.number="localConfig.powerDeratingFactor" min="0.2" max="1" step="0.01" type="range" />
          </label>
          <label>
            <span>坡度 {{ localConfig.gradientPermille.toFixed(0) }}‰</span>
            <input v-model.number="localConfig.gradientPermille" min="-25" max="25" step="1" type="range" />
          </label>
        </div>

        <div class="button-row">
          <button type="button" @click="stepDemo">单步</button>
          <button type="button" @click="resetDemo">重置</button>
        </div>
      </aside>
      <!-- Driver Cab Panel -->
      <aside class="control-board driver-cab-panel">
        <div class="section-heading compact">
          <div>
            <span>司机台</span>
            <h2>驾驶控制输入</h2>
          </div>
          <span class="connection-pill" :class="backendState" style="font-size:11px">
            {{ selectedLiveTrain ? selectedLiveTrain.id : '—' }}
          </span>
        </div>
        <div style="display:flex;gap:6px;margin-bottom:12px;align-items:center">
          <select v-model="driverCabInput.selectedTrainId" style="flex:1;padding:4px 8px;border:1px solid #d9e2ec;border-radius:6px;font-size:13px;background:#fff">
            <option v-for="id in trainIds" :key="id" :value="id">{{ id }}</option>
          </select>
        </div>
        <div style="margin-bottom:14px">
          <div style="font-size:12px;font-weight:700;color:#475569;margin-bottom:6px">🎛️ 主手柄</div>
          <div style="display:flex;align-items:center;gap:10px">
            <div style="text-align:center;min-width:44px">
              <div style="font-size:10px;color:#627084">牵引</div>
              <div style="font-size:16px;font-weight:700;color:#3e8e6a">{{ driverCabInput.masterHandleState === 'TRACTION' ? driverCabInput.tractionNotchPercent : 0 }}%</div>
            </div>
            <input type="range" min="-100" max="100" value="0" step="1" style="flex:1;accent-color:#1f78b4;height:6px" @input="(e) => updateMasterHandle(parseInt((e.target as HTMLInputElement).value))" />
            <div style="text-align:center;min-width:44px">
              <div style="font-size:10px;color:#627084">制动</div>
              <div style="font-size:16px;font-weight:700;color:#c24132">{{ driverCabInput.masterHandleState === 'BRAKE' || driverCabInput.masterHandleState === 'FAST_BRAKE' ? driverCabInput.brakeNotchPercent : 0 }}%</div>
            </div>
          </div>
          <div style="display:flex;justify-content:space-between;font-size:9px;color:#94a3b8;padding:0 4px"><span>快速</span><span>制动</span><span>零位</span><span>牵引</span></div>
        </div>
        <div style="display:grid;grid-template-columns:repeat(4,1fr);gap:6px;margin-bottom:10px">
          <button type="button" class="cab-btn" :class="{ active: !driverCabInput.keySwitchLocked }" @click="driverCabInput.keySwitchLocked = !driverCabInput.keySwitchLocked">🔑 {{ driverCabInput.keySwitchLocked ? '关闭' : '激活' }}</button>
          <button type="button" class="cab-btn dir" :class="{ active: true }" @click="cycleDirection()">{{ driverCabInput.directionHandleState === 'FORWARD' ? '⬆️前进' : driverCabInput.directionHandleState === 'BACKWARD' ? '⬇️后退' : '⏸️零位' }}</button>
          <button type="button" class="cab-btn eb" :class="{ active: driverCabInput.emergencyBrakeButtonLocked }" @click="driverCabInput.emergencyBrakeButtonLocked = !driverCabInput.emergencyBrakeButtonLocked">🛑 {{ driverCabInput.emergencyBrakeButtonLocked ? '⚠紧急' : '紧急' }}</button>
          <button type="button" class="cab-btn" :class="{ active: !driverCabInput.doorsClosedLockedIndicator }" @click="driverCabInput.doorsClosedLockedIndicator = !driverCabInput.doorsClosedLockedIndicator">🚪 {{ driverCabInput.doorsClosedLockedIndicator ? '关门' : '开门' }}</button>
        </div>
        <div style="display:grid;grid-template-columns:repeat(3,1fr);gap:6px;margin-bottom:10px">
          <button type="button" class="cab-btn ato" :class="{ active: driverCabInput.atoModeActive }" @click="driverCabInput.atoModeActive = !driverCabInput.atoModeActive">🤖 {{ driverCabInput.atoModeActive ? 'ATO' : '手动' }}</button>
          <button type="button" class="cab-btn" :class="{ active: driverCabInput.doorModeSwitchState !== 'SEMI_AUTOMATIC' }" @click="cycleDoorMode()">🚪⚙️ {{ { AUTOMATIC: '自动', SEMI_AUTOMATIC: '半自动', MANUAL: '手动' }[driverCabInput.doorModeSwitchState] }}</button>
          <button type="button" class="cab-btn ato" @click="atoStart()">▶️ ATO发车</button>
        </div>
        <div style="display:grid;grid-template-columns:1fr 1fr;gap:8px">
          <button type="button" style="background:#1f6feb;color:#fff;border:1px solid #58a6ff;border-radius:6px;padding:8px;font-weight:700;font-size:13px;cursor:pointer" :disabled="driverCabInput.sendPending" @click="sendPlcInput">{{ driverCabInput.sendPending ? '⏳ 发送中…' : '📤 发送 PLC 指令' }}</button>
          <div style="display:flex;align-items:center;justify-content:center;font-size:12px;font-weight:600;color:#475569;background:#f8fafc;border:1px solid #e2e8f0;border-radius:6px;padding:4px">{{ driverCabInput.sendResult || '就绪' }}</div>
        </div>
        <div v-if="selectedLiveTrain" style="margin-top:14px;padding:10px;background:#f8fafc;border:1px solid #e2e8f0;border-radius:8px">
          <div style="font-size:11px;font-weight:700;color:#475569;margin-bottom:6px">📋 实时车辆状态</div>
          <div style="display:grid;grid-template-columns:1fr 1fr;gap:4px 12px;font-size:11px">
            <span style="color:#627084">速度</span><span style="font-weight:600;text-align:right">{{ (selectedLiveTrain.speedMetersPerSecond * 3.6).toFixed(1) }} km/h</span>
            <span style="color:#627084">加速度</span><span style="font-weight:600;text-align:right">{{ (selectedLiveTrain.accelerationMetersPerSecondSquared || 0).toFixed(2) }} m/s²</span>
            <span style="color:#627084">运行模式</span><span style="font-weight:600;text-align:right">{{ selectedLiveTrain.operationMode || '—' }}</span>
            <span style="color:#627084">动力学状态</span><span style="font-weight:600;text-align:right">{{ selectedLiveTrain.dynamicsState || '—' }}</span>
            <span style="color:#627084">牵引力</span><span style="font-weight:600;text-align:right;color:#3e8e6a">{{ formatForce(selectedLiveTrain.tractionForceNewtons) }}</span>
            <span style="color:#627084">制动力</span><span style="font-weight:600;text-align:right;color:#c24132">{{ formatForce(selectedLiveTrain.brakeForceNewtons) }}</span>
            <span style="color:#627084">车门</span><span style="font-weight:600;text-align:right">{{ selectedLiveTrain.doorState || '—' }}</span>
            <span style="color:#627084">数据质量</span><span style="font-weight:600;text-align:right">{{ selectedLiveTrain.dataQuality || '—' }}</span>
            <span style="color:#627084">网压</span><span style="font-weight:600;text-align:right">{{ Math.round((selectedLiveTrain as any).railVoltage || 0) }} V</span>
            <span style="color:#627084">能耗</span><span style="font-weight:600;text-align:right">{{ (selectedLiveTrain.energyConsumedKwh || 0).toFixed(1) }} kWh</span>
          </div>
        </div>
      </aside>
    </section>
  </main>
</template>

<style scoped>
.app-shell {
  min-height: 100vh;
  padding: 28px;
  background:
    linear-gradient(180deg, #f8fafc 0%, #eef3f8 48%, #e8eef5 100%);
  color: #172033;
}

.topbar,
.live-strip,
.power-linkage,
.simulator-grid {
  width: min(1480px, 100%);
  margin: 0 auto;
}

.topbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 20px;
  padding: 18px 0 22px;
}

.eyebrow,
.section-heading span,
.telemetry-grid span,
.live-copy span,
.live-metrics span {
  display: block;
  color: #627084;
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

h1 {
  margin-top: 4px;
  font-size: 30px;
  line-height: 1.15;
}

h2 {
  margin-top: 4px;
  font-size: 22px;
  line-height: 1.2;
}

h3 {
  margin-top: 3px;
  font-size: 18px;
  line-height: 1.2;
}

button {
  min-height: 38px;
  border: 1px solid #cbd5e1;
  border-radius: 7px;
  padding: 0 14px;
  background: #ffffff;
  color: #172033;
  font: inherit;
  font-weight: 700;
  cursor: pointer;
  transition:
    border-color 160ms ease,
    box-shadow 160ms ease,
    transform 160ms ease;
}

button:hover:not(:disabled) {
  border-color: #1f78b4;
  box-shadow: 0 8px 18px rgba(31, 120, 180, 0.12);
  transform: translateY(-1px);
}

button:disabled {
  cursor: wait;
  opacity: 0.58;
}

.topbar-actions,
.button-row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: flex-end;
  gap: 10px;
}

.connection-pill,
.state-badge {
  display: inline-flex;
  min-height: 34px;
  align-items: center;
  border-radius: 999px;
  padding: 0 12px;
  font-size: 13px;
  font-weight: 800;
}

.connection-pill {
  border: 1px solid #d8dee8;
  background: #ffffff;
  color: #4b5565;
}

.connection-pill.live {
  border-color: #92d4aa;
  background: #eaf8ef;
  color: #126237;
}

.connection-pill.offline {
  border-color: #f3b8b8;
  background: #fff0f0;
  color: #9c2626;
}

.live-strip {
  display: grid;
  grid-template-columns: minmax(220px, 0.7fr) minmax(0, 1.8fr);
  gap: 14px;
  margin-bottom: 16px;
}

.live-copy,
.live-metrics,
.train-board,
.control-board,
.driver-cab-panel {
  border: 1px solid #d9e2ec;
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.92);
  box-shadow: 0 18px 40px rgba(15, 23, 42, 0.08);
}

.driver-cab-panel {
  align-self: start;
  padding: 16px;
}

.driver-cab-panel select {
  font-family: inherit;
}

.cab-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 3px;
  min-height: 34px;
  border: 1px solid #cbd5e1;
  border-radius: 6px;
  padding: 4px 6px;
  background: #ffffff;
  color: #475569;
  font-size: 11px;
  font-weight: 700;
  cursor: pointer;
  transition: all 0.12s;
}
.cab-btn:hover { border-color: #1f78b4; background: #f0f7ff; }
.cab-btn.active { border-color: #1f78b4; background: #e0f0ff; color: #145f91; }
.cab-btn.dir.active { border-color: #3e8e6a; background: #eaf8ef; color: #126237; }
.cab-btn.eb.active { border-color: #c24132; background: #fff0f0; color: #9c2626; }
.cab-btn.ato.active { border-color: #1f78b4; background: #edf7ff; color: #145f91; }
.cab-btn:disabled { opacity: 0.5; cursor: wait; }

.live-copy {
  display: grid;
  align-content: center;
  gap: 5px;
  padding: 16px;
}

.live-copy strong {
  font-size: 15px;
}

.live-metrics {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  overflow: hidden;
}

.live-metrics > div {
  padding: 15px 16px;
  border-left: 1px solid #e2e8f0;
}

.live-metrics > div:first-child {
  border-left: 0;
}

.live-metrics strong {
  display: block;
  margin-top: 5px;
  overflow-wrap: anywhere;
  font-size: 17px;
}

.power-linkage {
  margin-bottom: 16px;
  border: 1px solid #d9e2ec;
  border-radius: 10px;
  padding: 20px;
  background:
    linear-gradient(135deg, rgba(234, 248, 239, 0.86), rgba(255, 255, 255, 0.92) 38%),
    #ffffff;
  box-shadow: 0 18px 40px rgba(15, 23, 42, 0.08);
}

.power-summary {
  display: grid;
  justify-items: end;
  gap: 6px;
  text-align: right;
}

.quality-pill {
  display: inline-flex;
  min-height: 28px;
  align-items: center;
  border: 1px solid #9ccdb0;
  border-radius: 999px;
  padding: 0 10px;
  background: #eaf8ef;
  color: #126237;
  font-size: 12px;
  font-weight: 900;
}

.power-diagram {
  display: grid;
  gap: 12px;
  border: 1px solid #d9e2ec;
  border-radius: 10px;
  padding: 14px;
  background:
    linear-gradient(#eef3f8 1px, transparent 1px),
    linear-gradient(90deg, rgba(31, 120, 180, 0.1) 1px, transparent 1px),
    rgba(255, 255, 255, 0.8);
  background-size: 100% 44px, 72px 100%, auto;
}

.diagram-axis {
  display: flex;
  justify-content: space-between;
  color: #627084;
  font-size: 12px;
  font-weight: 800;
}

.diagram-row {
  display: grid;
  grid-template-columns: 84px minmax(0, 1fr);
  align-items: center;
  gap: 12px;
}

.diagram-label {
  color: #273448;
  font-size: 13px;
  font-weight: 900;
}

.diagram-lane {
  position: relative;
  min-height: 76px;
  border-radius: 9px;
  overflow: hidden;
}

.track-lane {
  background:
    repeating-linear-gradient(90deg, rgba(96, 112, 134, 0.32) 0 2px, transparent 2px 42px),
    linear-gradient(180deg, #f8fafc, #eef3f8);
}

.track-lane::before,
.track-lane::after {
  position: absolute;
  right: 0;
  left: 0;
  height: 3px;
  border-radius: 999px;
  background: #607086;
  content: '';
}

.track-lane::before {
  top: 26px;
}

.track-lane::after {
  bottom: 26px;
}

.power-lane {
  min-height: 104px;
  background:
    linear-gradient(90deg, rgba(31, 120, 180, 0.11), rgba(62, 142, 106, 0.12)),
    #f8fafc;
}

.track-segment-band,
.power-section-band {
  position: absolute;
  top: 10px;
  bottom: 10px;
  display: grid;
  align-content: center;
  gap: 2px;
  border: 1px solid rgba(96, 112, 134, 0.2);
  border-radius: 8px;
  padding: 6px 9px;
  overflow: hidden;
  background: rgba(255, 255, 255, 0.72);
  color: #172033;
  font-size: 12px;
  box-shadow: inset 0 -3px 0 rgba(96, 112, 134, 0.16);
}

.track-segment-band b,
.power-section-band b {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.track-segment-band small,
.power-section-band small {
  overflow: hidden;
  color: #627084;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.track-segment-band.occupied {
  border-color: #e0a54c;
  background: rgba(255, 248, 223, 0.86);
}

.track-segment-band.fault {
  border-color: #e89a9a;
  background: rgba(255, 240, 240, 0.88);
}

.power-section-band {
  top: 14px;
  bottom: 34px;
  border-color: #96c9e9;
  background: rgba(237, 247, 255, 0.88);
  box-shadow: inset 0 -4px 0 #1f78b4;
}

.power-section-band.status-undervoltage,
.power-section-card.status-undervoltage {
  border-color: #e7c56f;
}

.power-section-band.status-deenergized,
.power-section-band.status-isolated,
.power-section-band.status-tripped,
.power-section-card.status-deenergized,
.power-section-card.status-isolated,
.power-section-card.status-tripped {
  border-color: #e89a9a;
}

.power-train-marker {
  position: absolute;
  z-index: 3;
  bottom: 8px;
  transform: translateX(-50%);
  display: grid;
  justify-items: center;
  min-width: 132px;
  color: #172033;
  font-size: 12px;
  font-weight: 900;
}

.power-train-marker i {
  width: 58px;
  height: 30px;
  border: 2px solid #172033;
  border-radius: 8px 8px 5px 5px;
  background: #ffffff;
  box-shadow: inset 0 -8px 0 #c24132;
}

.power-empty {
  margin-top: 12px;
  border: 1px dashed #b7c4d2;
  border-radius: 8px;
  padding: 14px;
  color: #627084;
  font-weight: 800;
}

.power-section-cards {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
  margin-top: 14px;
}

.power-section-card {
  border: 1px solid #d9e2ec;
  border-radius: 10px;
  padding: 16px;
  background: #ffffff;
}

.power-section-card header {
  display: flex;
  justify-content: space-between;
  gap: 14px;
  margin-bottom: 14px;
}

.power-section-card header span,
.power-detail-grid span,
.voltage-row span {
  display: block;
  color: #627084;
  font-size: 12px;
  font-weight: 800;
}

.power-section-card header > strong {
  align-self: start;
  border-radius: 999px;
  padding: 6px 10px;
  background: #f1f5f9;
  color: #273448;
  font-size: 12px;
  white-space: nowrap;
}

.power-section-card.comparison-matched header > strong {
  background: #eaf8ef;
  color: #126237;
}

.power-section-card.comparison-deviated header > strong {
  background: #fff8df;
  color: #855b12;
}

.power-section-card.comparison-diverged header > strong {
  background: #fff0f0;
  color: #9c2626;
}

.voltage-bars {
  display: grid;
  gap: 8px;
}

.voltage-row {
  display: grid;
  grid-template-columns: 72px minmax(0, 1fr) 112px;
  align-items: center;
  gap: 10px;
}

.voltage-row i {
  height: 10px;
  border-radius: 999px;
  background: #e2e8f0;
  overflow: hidden;
}

.voltage-row b {
  display: block;
  height: 100%;
  border-radius: inherit;
}

.voltage-row.central b {
  background: #1f78b4;
}

.voltage-row.external b {
  background: #3e8e6a;
}

.voltage-row strong {
  text-align: right;
  font-size: 13px;
}

.power-detail-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 9px;
  margin-top: 13px;
}

.power-detail-grid > div {
  min-height: 68px;
  display: grid;
  align-content: center;
  gap: 3px;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  padding: 9px;
  background: #f8fafc;
}

.power-detail-grid strong,
.power-detail-grid small {
  overflow-wrap: anywhere;
}

.power-detail-grid strong {
  color: #172033;
  font-size: 14px;
}

.power-detail-grid small {
  color: #627084;
  font-size: 11px;
  font-weight: 700;
}

.simulator-grid {
  display: grid;
  grid-template-columns: minmax(0, 1.55fr) minmax(340px, 0.75fr) minmax(320px, 0.7fr);
  gap: 16px;
}

.train-board,
.control-board {
  padding: 20px;
}

.section-heading {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 14px;
  margin-bottom: 18px;
}

.section-heading.compact {
  align-items: center;
}

.state-badge {
  max-width: 46%;
  justify-content: center;
  border: 1px solid #d8dee8;
  background: #f8fafc;
  color: #475569;
  text-align: center;
}

.state-badge.success {
  border-color: #86d29e;
  background: #eaf8ef;
  color: #126237;
}

.state-badge.warning {
  border-color: #e7c56f;
  background: #fff8df;
  color: #855b12;
}

.state-badge.danger {
  border-color: #f3b8b8;
  background: #fff0f0;
  color: #9c2626;
}

.state-badge.info {
  border-color: #95c7ec;
  background: #edf7ff;
  color: #145f91;
}

.track-map {
  margin-top: 6px;
  padding: 14px 0 6px;
}

.track-labels {
  display: flex;
  justify-content: space-between;
  margin-bottom: 12px;
  color: #627084;
  font-size: 12px;
  font-weight: 700;
}

.rail {
  position: relative;
  height: 72px;
  border-radius: 8px;
  background:
    linear-gradient(90deg, rgba(31, 120, 180, 0.16), rgba(31, 120, 180, 0.05)),
    repeating-linear-gradient(90deg, #d9e2ec 0 2px, transparent 2px 48px);
  overflow: hidden;
}

.rail::before,
.rail::after {
  position: absolute;
  right: 0;
  left: 0;
  height: 3px;
  border-radius: 999px;
  background: #607086;
  content: '';
}

.rail::before {
  top: 28px;
}

.rail::after {
  bottom: 28px;
}

.authority-band {
  position: absolute;
  top: 0;
  bottom: 0;
  left: 0;
  background: rgba(62, 142, 106, 0.18);
}

.marker,
.train-marker {
  position: absolute;
  top: 7px;
  transform: translateX(-50%);
  z-index: 2;
  display: grid;
  justify-items: center;
  gap: 2px;
  color: #344155;
  font-size: 12px;
  font-weight: 800;
}

.marker i {
  width: 2px;
  height: 38px;
  background: currentColor;
}

.station-marker {
  color: #8a5a0d;
}

.authority-marker {
  color: #126237;
}

.train-marker {
  top: 15px;
  min-width: 106px;
  color: #172033;
}

.train-marker i {
  width: 52px;
  height: 30px;
  border: 2px solid #172033;
  border-radius: 7px 7px 5px 5px;
  background: #ffffff;
  box-shadow: inset 0 -8px 0 #1f78b4;
}

.speed-panel {
  display: grid;
  grid-template-columns: 136px minmax(0, 1fr);
  align-items: stretch;
  gap: 16px;
  margin-top: 20px;
}

.speed-readout {
  display: grid;
  align-content: center;
  border: 1px solid #d9e2ec;
  border-radius: 8px;
  padding: 14px;
  background: #f8fafc;
}

.speed-readout span,
.speed-readout small {
  color: #627084;
  font-size: 12px;
  font-weight: 800;
}

.speed-readout strong {
  font-size: 34px;
  line-height: 1;
}

.speed-chart {
  width: 100%;
  min-height: 168px;
  border: 1px solid #d9e2ec;
  border-radius: 8px;
  background:
    linear-gradient(#eef3f8 1px, transparent 1px),
    linear-gradient(90deg, #eef3f8 1px, transparent 1px),
    #ffffff;
  background-size: 100% 40px, 64px 100%, auto;
}

.chart-axis {
  stroke: #9aa7b8;
  stroke-width: 1;
}

.speed-line,
.limit-line {
  fill: none;
  stroke-linecap: round;
  stroke-linejoin: round;
}

.speed-line {
  stroke: #1f78b4;
  stroke-width: 4;
}

.limit-line {
  stroke: #c24132;
  stroke-dasharray: 8 8;
  stroke-width: 3;
}

.chart-legend {
  grid-column: 2;
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  color: #4b5565;
  font-size: 12px;
  font-weight: 800;
}

.chart-legend span {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.chart-legend i {
  width: 18px;
  height: 3px;
  border-radius: 999px;
}

.legend-speed {
  background: #1f78b4;
}

.legend-limit {
  background: #c24132;
}

.telemetry-grid {
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
  gap: 10px;
  margin-top: 18px;
}

.telemetry-grid > div {
  min-height: 76px;
  display: grid;
  align-content: center;
  gap: 6px;
  border: 1px solid #d9e2ec;
  border-radius: 8px;
  padding: 12px;
  background: #ffffff;
}

.telemetry-grid strong {
  overflow-wrap: anywhere;
  font-size: 18px;
}

.control-board {
  align-self: start;
}

.run-switch,
.constraint-toggle {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  color: #243044;
  font-weight: 800;
}

.run-switch input,
.constraint-toggle input {
  width: 18px;
  height: 18px;
  accent-color: #1f78b4;
}

.scenario-tabs {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 8px;
}

.scenario-tabs button {
  min-height: 36px;
  padding: 0 8px;
  font-size: 13px;
}

.scenario-tabs button.active {
  border-color: #1f78b4;
  background: #eaf5fc;
  color: #145f91;
}

.state-chain {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 8px;
  margin-top: 16px;
}

.state-node {
  min-height: 36px;
  display: flex;
  align-items: center;
  justify-content: center;
  border: 1px solid #cbd5e1;
  border-radius: 7px;
  padding: 0 8px;
  background: #ffffff;
  color: #475569;
  font-size: 12px;
  font-weight: 700;
  text-align: center;
}

.state-node.success.active {
  border-color: #3e8e6a;
  background: #eaf8ef;
  color: #126237;
}

.state-node.warning.active {
  border-color: #c4911f;
  background: #fff8df;
  color: #855b12;
}

.state-node.danger.active {
  border-color: #cc4c4c;
  background: #fff0f0;
  color: #9c2626;
}

.state-node.info.active {
  border-color: #1f78b4;
  background: #edf7ff;
  color: #145f91;
}

.state-node.neutral.active {
  border-color: #607086;
  background: #f1f5f9;
  color: #273448;
}

.constraint-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px 12px;
  margin-top: 18px;
  padding: 14px;
  border: 1px solid #d9e2ec;
  border-radius: 8px;
  background: #f8fafc;
}

.slider-stack {
  display: grid;
  gap: 12px;
  margin-top: 18px;
}

.slider-stack label {
  display: grid;
  gap: 7px;
  color: #273448;
  font-size: 13px;
  font-weight: 800;
}

.slider-stack input[type='range'] {
  width: 100%;
  accent-color: #1f78b4;
}

.button-row {
  margin-top: 18px;
}

.button-row button {
  flex: 1;
}

@media (max-width: 1120px) {
  .simulator-grid,
  .live-strip {
    grid-template-columns: 1fr;
  }

  .power-section-cards {
    grid-template-columns: 1fr;
  }

  .live-metrics {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .live-metrics > div:nth-child(odd) {
    border-left: 0;
  }

  .telemetry-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 720px) {
  .app-shell {
    padding: 18px;
  }

  .topbar,
  .section-heading,
  .speed-panel {
    grid-template-columns: 1fr;
  }

  .topbar,
  .section-heading {
    align-items: stretch;
    flex-direction: column;
  }

  .power-summary {
    justify-items: start;
    text-align: left;
  }

  .topbar-actions {
    justify-content: flex-start;
  }

  .state-badge {
    max-width: none;
  }

  .speed-panel {
    display: grid;
  }

  .chart-legend {
    grid-column: 1;
  }

  .live-metrics,
  .scenario-tabs,
  .state-chain,
  .constraint-grid,
  .telemetry-grid,
  .power-detail-grid {
    grid-template-columns: 1fr;
  }

  .diagram-row,
  .voltage-row {
    grid-template-columns: 1fr;
  }

  .voltage-row strong {
    text-align: left;
  }

  .live-metrics > div {
    border-left: 0;
    border-top: 1px solid #e2e8f0;
  }

  .live-metrics > div:first-child {
    border-top: 0;
  }
}
</style>
<<<<<<< HEAD

=======
>>>>>>> 3f3347648baf2fb973f4ad776bb1a9fd90fb93a4
