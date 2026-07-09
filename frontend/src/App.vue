<script setup lang="ts">
// Dependencies: vue>=3.5.13, echarts>=5.6.0
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import * as echarts from 'echarts'

type LayerKey = 'trains' | 'track' | 'power' | 'signals' | 'passengers'
type AlarmLevel = 1 | 2 | 3
type HeatView = 'network' | 'station' | 'carriage'
type CommandStatus = '已执行' | '待执行' | '异常'

interface TrainMonitorState {
  id: string
  serviceNo: string
  positionPercent: number
  speedKph: number
  loadRate: number
  faultCode: string
  section: string
}

interface TrackSegmentState {
  id: string
  name: string
  startPercent: number
  widthPercent: number
  occupancy: 'FREE' | 'OCCUPIED' | 'FAULT'
  speedLimitKph: number
}

interface PowerSectionState {
  id: string
  name: string
  startPercent: number
  widthPercent: number
  status: 'ENERGIZED' | 'LOST' | 'OVERRANGE'
  affectedTrains: string[]
}

interface StationPassengerState {
  name: string
  inbound: number
  outbound: number
  waiting: number
  capacity: number
  forecastRate: number
}

interface UnifiedAlarm {
  id: string
  time: string
  source: string
  location: string
  level: AlarmLevel
  description: string
  impact: string
  confirmed: boolean
  muted: boolean
}

interface DispatchCommandState {
  id: string
  type: string
  target: string
  status: CommandStatus
  deviation: string
}

interface LinkState {
  name: string
  status: '在线' | '延迟' | '中断'
  latencyMs: number
  lastPacket: string
}

const layerLabels: Record<LayerKey, string> = {
  trains: '车辆',
  track: '轨道',
  power: '供电',
  signals: '信号',
  passengers: '客流'
}

const activeLayers = ref<Record<LayerKey, boolean>>({
  trains: true,
  track: true,
  power: true,
  signals: true,
  passengers: true
})
const heatView = ref<HeatView>('network')
const alarmFilter = ref<'全部' | '未确认' | '一级' | '二级' | '三级'>('全部')
const soundEnabled = ref(true)
const selectedLocation = ref('人民广场')
const trainCrowdThreshold = ref(80)
const stationCrowdThreshold = ref(80)
const predictionWindowMinutes = ref(15)
const simulationClock = ref('08:35:24')

const stations = ['上京南', '科技园', '人民广场', '金融城', '会展中心', '机场北']

const trains = ref<TrainMonitorState[]>([
  { id: 'T101', serviceNo: 'G101', positionPercent: 12, speedKph: 62, loadRate: 46, faultCode: '', section: '上京南-科技园' },
  { id: 'T203', serviceNo: 'G203', positionPercent: 31, speedKph: 71, loadRate: 83, faultCode: '', section: '科技园-人民广场' },
  { id: 'T305', serviceNo: 'G305', positionPercent: 54, speedKph: 0, loadRate: 108, faultCode: 'VHL-23', section: '人民广场-金融城' },
  { id: 'T407', serviceNo: 'G407', positionPercent: 76, speedKph: 58, loadRate: 69, faultCode: '', section: '金融城-会展中心' },
  { id: 'T509', serviceNo: 'G509', positionPercent: 89, speedKph: 44, loadRate: 37, faultCode: '', section: '会展中心-机场北' }
])

const trackSegments = ref<TrackSegmentState[]>([
  { id: 'S1', name: '上京南-科技园', startPercent: 4, widthPercent: 17, occupancy: 'FREE', speedLimitKph: 80 },
  { id: 'S2', name: '科技园-人民广场', startPercent: 21, widthPercent: 19, occupancy: 'OCCUPIED', speedLimitKph: 80 },
  { id: 'S3', name: '人民广场-金融城', startPercent: 40, widthPercent: 19, occupancy: 'FAULT', speedLimitKph: 35 },
  { id: 'S4', name: '金融城-会展中心', startPercent: 59, widthPercent: 18, occupancy: 'OCCUPIED', speedLimitKph: 70 },
  { id: 'S5', name: '会展中心-机场北', startPercent: 77, widthPercent: 18, occupancy: 'FREE', speedLimitKph: 90 }
])

const powerSections = ref<PowerSectionState[]>([
  { id: 'P1', name: '牵引一区', startPercent: 4, widthPercent: 28, status: 'ENERGIZED', affectedTrains: [] },
  { id: 'P2', name: '牵引二区', startPercent: 32, widthPercent: 28, status: 'OVERRANGE', affectedTrains: ['T305'] },
  { id: 'P3', name: '牵引三区', startPercent: 60, widthPercent: 35, status: 'LOST', affectedTrains: ['T407', 'T509'] }
])

const passengerStations = ref<StationPassengerState[]>([
  { name: '上京南', inbound: 816, outbound: 642, waiting: 1320, capacity: 2600, forecastRate: 58 },
  { name: '科技园', inbound: 1320, outbound: 930, waiting: 2180, capacity: 2800, forecastRate: 82 },
  { name: '人民广场', inbound: 2260, outbound: 1640, waiting: 3560, capacity: 3200, forecastRate: 111 },
  { name: '金融城', inbound: 1740, outbound: 1480, waiting: 2460, capacity: 3000, forecastRate: 88 },
  { name: '会展中心', inbound: 980, outbound: 720, waiting: 1160, capacity: 2400, forecastRate: 54 },
  { name: '机场北', inbound: 660, outbound: 760, waiting: 860, capacity: 2200, forecastRate: 42 }
])

const alarms = ref<UnifiedAlarm[]>([
  { id: 'A001', time: '08:34:55', source: '车辆', location: 'T305', level: 3, description: '整列满载率超过100%，车辆故障码 VHL-23', impact: '影响后续 T407，预计影响旅客 1280 人', confirmed: false, muted: false },
  { id: 'A002', time: '08:34:12', source: '轨道信号', location: '人民广场-金融城', level: 3, description: '轨道电路红光带，列车接近故障区段', impact: '闭塞区段 S3，影响列车 T305/T407', confirmed: false, muted: false },
  { id: 'A003', time: '08:33:40', source: '供电', location: '牵引三区', level: 3, description: '接触轨失压，分区失电', impact: 'P3 内 T407/T509 降级运行', confirmed: false, muted: false },
  { id: 'A004', time: '08:32:19', source: '客流', location: '人民广场', level: 2, description: '站台滞留人数超过容量80%，预测15分钟后超容量', impact: '建议限流并调整发车间隔', confirmed: true, muted: false },
  { id: 'A005', time: '08:31:07', source: '通信', location: '客流接口', level: 1, description: '会展中心客流数据延迟', impact: '热力图使用最近缓存数据', confirmed: false, muted: true }
])

const dispatchCommands = ref<DispatchCommandState[]>([
  { id: 'D-18', type: '临时限速', target: 'S3', status: '已执行', deviation: '0 km/h' },
  { id: 'D-19', type: '调整间隔', target: 'G203-G305', status: '待执行', deviation: '+42 s' },
  { id: 'D-20', type: '跳停', target: 'T407/金融城', status: '异常', deviation: '+118 s' }
])

const linkStates = ref<LinkState[]>([
  { name: '车辆仿真', status: '在线', latencyMs: 42, lastPacket: '08:35:23' },
  { name: '轨道信号', status: '在线', latencyMs: 38, lastPacket: '08:35:24' },
  { name: '供电仿真', status: '延迟', latencyMs: 186, lastPacket: '08:35:21' },
  { name: '客流接口', status: '中断', latencyMs: 0, lastPacket: '08:33:08' }
])

const trendChartRef = ref<HTMLDivElement | null>(null)
let trendChart: echarts.ECharts | null = null
let clockTimer = 0
let dataTimer = 0

const unconfirmedAlarmCount = computed(() => alarms.value.filter((alarm) => !alarm.confirmed && !alarm.muted).length)
const onlineTrainCount = computed(() => trains.value.length)
const averageLoadRate = computed(() => Math.round(trains.value.reduce((sum, train) => sum + train.loadRate, 0) / trains.value.length))
const totalInbound = computed(() => passengerStations.value.reduce((sum, station) => sum + station.inbound, 0))
const totalOutbound = computed(() => passengerStations.value.reduce((sum, station) => sum + station.outbound, 0))
const overloadedStations = computed(() =>
  passengerStations.value.filter((station) => station.waiting / station.capacity >= stationCrowdThreshold.value / 100)
)
const capacityTopFive = computed(() => [...trains.value].sort((firstTrain, secondTrain) => secondTrain.loadRate - firstTrain.loadRate).slice(0, 5))

const operationMetrics = computed(() => [
  { label: '上线列车数', value: onlineTrainCount.value, unit: '列', status: 'normal' },
  { label: '准点率', value: 94.6, unit: '%', status: 'warning' },
  { label: '平均满载率', value: averageLoadRate.value, unit: '%', status: averageLoadRate.value >= trainCrowdThreshold.value ? 'warning' : 'normal' },
  { label: '运力利用率', value: 86.2, unit: '%', status: 'normal' },
  { label: '发车间隔执行率', value: 91.4, unit: '%', status: 'warning' }
])

const filteredAlarms = computed(() => {
  const alarmList = [...alarms.value].sort((firstAlarm, secondAlarm) => secondAlarm.level - firstAlarm.level)
  if (alarmFilter.value === '未确认') return alarmList.filter((alarm) => !alarm.confirmed)
  if (alarmFilter.value === '一级') return alarmList.filter((alarm) => alarm.level === 1)
  if (alarmFilter.value === '二级') return alarmList.filter((alarm) => alarm.level === 2)
  if (alarmFilter.value === '三级') return alarmList.filter((alarm) => alarm.level === 3)
  return alarmList
})

const selectedStation = computed(() => passengerStations.value.find((station) => station.name === selectedLocation.value) ?? passengerStations.value[0])
const selectedStationLoadRate = computed(() => Math.round((selectedStation.value.waiting / selectedStation.value.capacity) * 100))

function sanitizeNumericInput(rawValue: number, minimumValue: number, maximumValue: number): number {
  // 安全措施：限制配置输入范围，避免异常输入污染阈值计算和界面渲染。
  if (!Number.isFinite(rawValue)) return minimumValue
  return Math.min(Math.max(Math.round(rawValue), minimumValue), maximumValue)
}

function loadClass(loadRate: number): string {
  if (loadRate >= 100) return 'danger'
  if (loadRate >= trainCrowdThreshold.value) return 'warning'
  if (loadRate >= 50) return 'busy'
  return 'normal'
}

function stationHeatClass(station: StationPassengerState): string {
  const loadRate = Math.round((station.waiting / station.capacity) * 100)
  if (loadRate >= 100) return 'danger'
  if (loadRate >= stationCrowdThreshold.value) return 'warning'
  if (loadRate >= 50) return 'busy'
  return 'normal'
}

function acknowledgeAlarm(alarmId: string): void {
  const alarm = alarms.value.find((item) => item.id === alarmId)
  if (alarm) alarm.confirmed = true
}

function muteAlarm(alarmId: string): void {
  const alarm = alarms.value.find((item) => item.id === alarmId)
  if (alarm) alarm.muted = true
}

function resolveAlarmStation(alarm: UnifiedAlarm): string {
  if (stations.includes(alarm.location)) return alarm.location

  const relatedTrain = trains.value.find((train) => train.id === alarm.location || train.serviceNo === alarm.location)
  if (relatedTrain) return relatedTrain.section.split('-')[0]

  const relatedTrack = trackSegments.value.find((segment) => segment.name === alarm.location || segment.id === alarm.location)
  if (relatedTrack) return relatedTrack.name.split('-')[0]

  const relatedPower = powerSections.value.find((section) => section.name === alarm.location || section.id === alarm.location)
  if (relatedPower?.affectedTrains.length) {
    const affectedTrain = trains.value.find((train) => train.id === relatedPower.affectedTrains[0])
    if (affectedTrain) return affectedTrain.section.split('-')[0]
  }

  const locationStation = stations.find((station) => alarm.location.includes(station))
  if (locationStation) return locationStation

  return selectedLocation.value
}

function focusAlarm(alarm: UnifiedAlarm): void {
  selectedLocation.value = resolveAlarmStation(alarm)
}

function updateThresholds(): void {
  const normalizedTrainThreshold = sanitizeNumericInput(trainCrowdThreshold.value, 50, 100)
  const normalizedStationThreshold = sanitizeNumericInput(stationCrowdThreshold.value, 50, 100)
  const normalizedPredictionWindow = sanitizeNumericInput(predictionWindowMinutes.value, 5, 30)

  if (trainCrowdThreshold.value !== normalizedTrainThreshold) trainCrowdThreshold.value = normalizedTrainThreshold
  if (stationCrowdThreshold.value !== normalizedStationThreshold) stationCrowdThreshold.value = normalizedStationThreshold
  if (predictionWindowMinutes.value !== normalizedPredictionWindow) predictionWindowMinutes.value = normalizedPredictionWindow
}

function tickMockData(): void {
  // 性能策略：只更新小规模响应式数组中的必要字段，避免整页重建，单次刷新复杂度 O(n)。
  trains.value = trains.value.map((train, trainIndex) => ({
    ...train,
    positionPercent: Math.min(95, Math.max(5, train.positionPercent + (train.speedKph > 0 ? 0.4 + trainIndex * 0.03 : 0))),
    loadRate: Math.min(118, Math.max(32, train.loadRate + (trainIndex % 2 === 0 ? 1 : -1)))
  }))
  passengerStations.value = passengerStations.value.map((station, stationIndex) => ({
    ...station,
    waiting: Math.max(600, station.waiting + (stationIndex % 2 === 0 ? 24 : -16)),
    forecastRate: Math.min(120, Math.max(35, station.forecastRate + (stationIndex % 2 === 0 ? 1 : -1)))
  }))
  renderTrendChart()
}

function renderTrendChart(): void {
  if (!trendChartRef.value) return
  if (!trendChart) trendChart = echarts.init(trendChartRef.value)
  const baseSeries = [620, 760, 980, 1260, 1480, 1660, 1810, 1960, 2130, selectedStation.value.waiting]
  const forecastSeries = baseSeries.map((value, index) => Math.round(value * (1 + index * 0.012)))
  trendChart.setOption({
    grid: { left: 38, right: 18, top: 28, bottom: 34 },
    tooltip: { trigger: 'axis' },
    legend: { top: 0, textStyle: { color: '#5b677a' } },
    xAxis: {
      type: 'category',
      data: ['-45', '-40', '-35', '-30', '-25', '-20', '-15', '-10', '-5', '当前'],
      axisLine: { lineStyle: { color: '#b8c2d1' } },
      axisLabel: { color: '#6c7788' }
    },
    yAxis: {
      type: 'value',
      axisLabel: { color: '#6c7788' },
      splitLine: { lineStyle: { color: '#e6ebf2' } }
    },
    series: [
      { name: '实时客流', type: 'line', smooth: true, symbolSize: 6, data: baseSeries, lineStyle: { width: 3, color: '#2563eb' }, itemStyle: { color: '#2563eb' }, areaStyle: { color: 'rgba(37, 99, 235, 0.12)' } },
      { name: `${predictionWindowMinutes.value}分钟预测`, type: 'line', smooth: true, symbolSize: 6, data: forecastSeries, lineStyle: { width: 3, color: '#f59e0b', type: 'dashed' }, itemStyle: { color: '#f59e0b' } }
    ]
  })
}

watch([selectedLocation, predictionWindowMinutes], () => nextTick(renderTrendChart))

onMounted(() => {
  renderTrendChart()
  clockTimer = window.setInterval(() => {
    simulationClock.value = new Date().toLocaleTimeString('zh-CN', { hour12: false })
  }, 1000)
  dataTimer = window.setInterval(tickMockData, 3000)
  window.addEventListener('resize', renderTrendChart)
})

onBeforeUnmount(() => {
  window.clearInterval(clockTimer)
  window.clearInterval(dataTimer)
  window.removeEventListener('resize', renderTrendChart)
  trendChart?.dispose()
})
</script>

<template>
  <main class="monitor-shell">
    <header class="topbar">
      <section>
        <p class="eyebrow">Railway-Sim 综合监控中心</p>
        <h1>上京地铁运营态势监控</h1>
      </section>
      <section class="topbar-actions" aria-label="仿真状态">
        <span class="status-pill running">本地仿真数据</span>
        <span>{{ simulationClock }}</span>
        <button type="button" :class="['sound-button', { off: !soundEnabled }]" @click="soundEnabled = !soundEnabled">
          {{ soundEnabled ? '声警开启' : '声警关闭' }}
        </button>
      </section>
    </header>

    <section class="summary-grid" aria-label="运营指标">
      <article v-for="metric in operationMetrics" :key="metric.label" :class="['metric-card', metric.status]">
        <span>{{ metric.label }}</span>
        <strong>{{ metric.value }}<small>{{ metric.unit }}</small></strong>
      </article>
      <article class="metric-card danger">
        <span>未确认告警</span>
        <strong>{{ unconfirmedAlarmCount }}<small>条</small></strong>
      </article>
    </section>

    <section class="workspace-grid">
      <section class="panel map-panel">
        <div class="panel-title">
          <div>
            <h2>线路综合态势图</h2>
            <p>车辆、轨道、供电、信号、客流图层统一叠加</p>
          </div>
          <div class="layer-toggle" aria-label="图层控制">
            <label v-for="(_, layerKey) in activeLayers" :key="layerKey">
              <input v-model="activeLayers[layerKey]" type="checkbox" />
              {{ layerLabels[layerKey] }}
            </label>
          </div>
        </div>

        <div class="line-map">
          <div v-if="activeLayers.power" class="power-band">
            <span
              v-for="section in powerSections"
              :key="section.id"
              :class="['power-section', section.status.toLowerCase()]"
              :style="{ left: `${section.startPercent}%`, width: `${section.widthPercent}%` }"
            >{{ section.name }}</span>
          </div>

          <div class="rail-line">
            <span
              v-for="segment in trackSegments"
              :key="segment.id"
              :class="['track-segment', activeLayers.track ? segment.occupancy.toLowerCase() : 'muted']"
              :style="{ left: `${segment.startPercent}%`, width: `${segment.widthPercent}%` }"
              :title="`${segment.name} / 限速 ${segment.speedLimitKph}km/h`"
            ></span>
          </div>

          <button
            v-for="station in passengerStations"
            :key="station.name"
            type="button"
            :class="['station-node', stationHeatClass(station), { selected: selectedLocation === station.name }]"
            :style="{ left: `${8 + passengerStations.indexOf(station) * 17}%` }"
            @click="selectedLocation = station.name"
          >
            <span>{{ station.name }}</span>
          </button>

          <div v-if="activeLayers.trains" class="train-layer">
            <button
              v-for="train in trains"
              :key="train.id"
              type="button"
              :class="['train-marker', loadClass(train.loadRate), { fault: train.faultCode }]"
              :style="{ left: `${train.positionPercent}%` }"
              :title="`${train.serviceNo} ${train.speedKph}km/h 满载率${train.loadRate}%`"
            >
              {{ train.serviceNo }}
            </button>
          </div>

          <div v-if="activeLayers.signals" class="signal-layer">
            <span class="signal green" style="left: 18%"></span>
            <span class="signal yellow" style="left: 37%"></span>
            <span class="signal red blink" style="left: 56%"></span>
            <span class="switch reverse" style="left: 67%">↗</span>
            <span class="signal green" style="left: 82%"></span>
          </div>
        </div>

        <div class="map-details">
          <article>
            <h3>{{ selectedStation.name }}</h3>
            <p>候车 {{ selectedStation.waiting }} 人 / 容量 {{ selectedStation.capacity }} 人</p>
            <div class="progress"><span :class="stationHeatClass(selectedStation)" :style="{ width: `${Math.min(selectedStationLoadRate, 100)}%` }"></span></div>
          </article>
          <article>
            <h3>故障影响链</h3>
            <p>牵引三区失电 → T407/T509 降级 → 会展中心断面运力下降 → 预计影响旅客 2140 人</p>
          </article>
          <article>
            <h3>调度动作</h3>
            <p>临时限速 S3 已执行，G203-G305 间隔调整待执行</p>
          </article>
        </div>
      </section>

      <aside class="panel alarm-panel">
        <div class="panel-title compact">
          <h2>统一告警</h2>
          <select v-model="alarmFilter" aria-label="告警筛选">
            <option>全部</option>
            <option>未确认</option>
            <option>一级</option>
            <option>二级</option>
            <option>三级</option>
          </select>
        </div>
        <div class="alarm-list">
          <article v-for="alarm in filteredAlarms" :key="alarm.id" :class="['alarm-item', `level-${alarm.level}`, { confirmed: alarm.confirmed, muted: alarm.muted }]">
            <div>
              <span>{{ alarm.time }}</span>
              <strong>{{ alarm.source }} · {{ alarm.location }}</strong>
            </div>
            <p>{{ alarm.description }}</p>
            <small>{{ alarm.impact }}</small>
            <footer>
              <button type="button" @click="focusAlarm(alarm)">定位</button>
              <button type="button" :disabled="alarm.confirmed" @click="acknowledgeAlarm(alarm.id)">确认</button>
              <button type="button" :disabled="alarm.muted" @click="muteAlarm(alarm.id)">屏蔽</button>
            </footer>
          </article>
        </div>
      </aside>
    </section>

    <section class="lower-grid">
      <section class="panel">
        <div class="panel-title compact">
          <h2>客流热力与趋势</h2>
          <div class="segmented">
            <button type="button" :class="{ active: heatView === 'network' }" @click="heatView = 'network'">线网</button>
            <button type="button" :class="{ active: heatView === 'station' }" @click="heatView = 'station'">车站</button>
            <button type="button" :class="{ active: heatView === 'carriage' }" @click="heatView = 'carriage'">车厢</button>
          </div>
        </div>
        <div class="passenger-grid">
          <article v-for="station in passengerStations" :key="station.name" :class="['heat-tile', stationHeatClass(station)]">
            <span>{{ station.name }}</span>
            <strong>{{ Math.round((station.waiting / station.capacity) * 100) }}%</strong>
            <small>预测 {{ station.forecastRate }}%</small>
          </article>
        </div>
        <div ref="trendChartRef" class="trend-chart" aria-label="客流趋势图"></div>
      </section>

      <section class="panel">
        <div class="panel-title compact">
          <h2>车辆与断面满载率 Top5</h2>
          <span>进 {{ totalInbound }} / 出 {{ totalOutbound }}</span>
        </div>
        <div class="train-table">
          <div v-for="train in capacityTopFive" :key="train.id" class="table-row">
            <span>{{ train.serviceNo }}</span>
            <span>{{ train.section }}</span>
            <strong :class="loadClass(train.loadRate)">{{ train.loadRate }}%</strong>
          </div>
        </div>
        <h3>超容量车站</h3>
        <div class="station-list">
          <span v-for="station in overloadedStations" :key="station.name">{{ station.name }}</span>
        </div>
      </section>

      <section class="panel">
        <div class="panel-title compact">
          <h2>调度与通信</h2>
          <span>链路心跳</span>
        </div>
        <div class="command-list">
          <article v-for="command in dispatchCommands" :key="command.id">
            <strong>{{ command.type }}</strong>
            <span>{{ command.target }}</span>
            <em :class="command.status">{{ command.status }}</em>
            <small>偏差 {{ command.deviation }}</small>
          </article>
        </div>
        <div class="link-list">
          <article v-for="link in linkStates" :key="link.name">
            <span :class="['link-dot', link.status]"></span>
            <strong>{{ link.name }}</strong>
            <small>{{ link.status }} · {{ link.latencyMs }}ms · {{ link.lastPacket }}</small>
          </article>
        </div>
      </section>

      <section class="panel settings-panel">
        <div class="panel-title compact">
          <h2>阈值配置</h2>
          <span>分级权限配置项</span>
        </div>
        <label>
          <span>列车拥挤阈值</span>
          <input v-model.number="trainCrowdThreshold" type="number" min="50" max="100" @change="updateThresholds" />
        </label>
        <label>
          <span>车站容量阈值</span>
          <input v-model.number="stationCrowdThreshold" type="number" min="50" max="100" @change="updateThresholds" />
        </label>
        <label>
          <span>预测周期分钟</span>
          <input v-model.number="predictionWindowMinutes" type="number" min="5" max="30" @change="updateThresholds" />
        </label>
        <p>当前为本地仿真数据模式，不会请求后端 `/api` 或 `/ws`；接入后端时再启用数据适配层。</p>
      </section>
    </section>
  </main>
</template>

<style scoped>
.app {
  min-height: 100vh;
  padding-bottom: 24px;
}

.top-bar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 12px 20px 0;
  max-width: 1200px;
  margin: 0 auto;
}

.brand {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.hint {
  color: #64748b;
  font-size: 12px;
}

.tabs {
  display: flex;
  gap: 8px;
}

.tabs button {
  border: 1px solid #cbd5e1;
  background: #fff;
  color: #475569;
  border-radius: 8px;
  padding: 10px 16px;
  cursor: pointer;
}

.tabs button.active {
  background: #2563eb;
  border-color: #2563eb;
  color: #fff;
}

.global-controls {
  max-width: 1200px;
  margin: 12px auto 0;
}

.auto-btn {
  border: 1px solid #cbd5e1;
  background: #fff;
  color: #334155;
  border-radius: 8px;
  padding: 10px 16px;
  cursor: pointer;
  font-size: 14px;
  font-weight: 500;
}

.auto-btn.running {
  background: #059669;
  border-color: #059669;
  color: #fff;
}

.banner {
  max-width: 1200px;
  margin: 12px auto 0;
  padding: 10px 14px;
  border-radius: 8px;
  font-size: 13px;
}

.banner.error {
  background: #fef2f2;
  color: #b91c1c;
}

.banner.warn {
  background: #fffbeb;
  color: #92400e;
}

.banner.info {
  background: #eff6ff;
  color: #1e40af;
}

.app-shell {
  max-width: 720px;
  margin: 40px auto;
  padding: 0 20px;
  line-height: 1.6;
}

h1 {
  margin: 0 0 8px;
  font-size: 32px;
}

p {
  margin: 0 0 12px;
  color: #64748b;
}

code {
  background: #f1f5f9;
  padding: 2px 6px;
  border-radius: 4px;
  font-size: 12px;
}
</style>
