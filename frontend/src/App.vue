<script setup lang="ts">
// Dependencies: vue>=3.5.13, echarts>=5.6.0
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import * as echarts from 'echarts'
import topologyTrainUrl from './assets/topology-train.svg'
import { simulationApi } from './api/rest'
import { simulationSocket } from './api/ws'
import type { SimulationSnapshot } from './types/simulation'
import DispatchLoopDebugView from './views/dispatch/DispatchLoopDebugView.vue'

type ActivePage = 'monitor' | 'dispatchLoop'
type LayerKey = 'trains' | 'track' | 'power' | 'signals' | 'passengers'
type AlarmLevel = 1 | 2 | 3
type HeatView = 'network' | 'station' | 'carriage'
type CommandStatus = '已执行' | '待执行' | '异常'
type TopologyView = 'overview' | 'station' | 'route'

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

interface TopologyNode {
  id: string
  label: string
  type: 'station' | 'switch' | 'junction' | 'depot'
  x: number
  y: number
  importance: number
  stationName?: string
}

interface TopologyEdge {
  id: string
  source: string
  target: string
  segmentId: string
  lengthMeters: number
  speedLimitKph: number
  status: 'FREE' | 'OCCUPIED' | 'FAULT'
  powerSectionId: string
  routeIds: string[]
  detailCount: number
}

interface TopologyRoute {
  id: string
  name: string
  segmentIds: string[]
  description: string
}

interface TopologySignal {
  id: string
  edgeId: string
  ratio: number
  status: 'green' | 'yellow' | 'red'
}

interface TopologyTrackPoint {
  x: number
  y: number
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
const topologyView = ref<TopologyView>('overview')
const selectedRouteId = ref('R01')
const activePage = ref<ActivePage>('monitor')
const backendConnected = ref(false)
const backendErrorMessage = ref('')
const backendTick = ref(0)
const backendStatus = ref('STOPPED')

const stations = ['上京南', '科技园', '人民广场', '金融城', '会展中心', '机场北']

const trains = ref<TrainMonitorState[]>([
  { id: 'T101', serviceNo: 'G101', positionPercent: 12, speedKph: 62, loadRate: 46, faultCode: '', section: '上京南-科技园' },
  { id: 'T203', serviceNo: 'G203', positionPercent: 31, speedKph: 71, loadRate: 83, faultCode: '', section: '科技园-人民广场' },
  { id: 'T305', serviceNo: 'G305', positionPercent: 54, speedKph: 0, loadRate: 108, faultCode: 'VHL-23', section: '人民广场-金融城' },
  { id: 'T407', serviceNo: 'G407', positionPercent: 76, speedKph: 58, loadRate: 69, faultCode: '', section: '金融城-会展中心' },
  { id: 'T509', serviceNo: 'G509', positionPercent: 89, speedKph: 44, loadRate: 37, faultCode: '', section: '会展中心-机场北' }
])

// 正线6站均分0~6250m (maxPositionMeters=6250)
// T01:0-1250m→2%  T02:1250-2500m→19%  T03:2500-3750m→38%
// T04:3750-5000m→57%  T05:5000-6250m→76%
const trackSegments = ref<TrackSegmentState[]>([
  // 正线 (0-6250m起点百分比/宽度)
  { id: 'T01', name: '上京南-科创园', startPercent: 2, widthPercent: 18, occupancy: 'FREE', speedLimitKph: 72 },
  { id: 'T02', name: '科创园-中央公园', startPercent: 20, widthPercent: 20, occupancy: 'FREE', speedLimitKph: 80 },
  { id: 'T03', name: '中央公园-北城', startPercent: 40, widthPercent: 20, occupancy: 'FREE', speedLimitKph: 80 },
  { id: 'T04', name: '北城-会展中心', startPercent: 60, widthPercent: 20, occupancy: 'FREE', speedLimitKph: 72 },
  { id: 'T05', name: '会展中心-上京北', startPercent: 80, widthPercent: 16, occupancy: 'FREE', speedLimitKph: 80 },
  // 车辆段 (0-1250m共享里程)
  { id: 'T11', name: '试车线-车辆段', startPercent: 2, widthPercent: 5, occupancy: 'FREE', speedLimitKph: 36 },
  { id: 'T12', name: '车辆段-科创园', startPercent: 7, widthPercent: 13, occupancy: 'FREE', speedLimitKph: 43 },
  // 北侧绕行 (1250-5000m与正线平行)
  { id: 'T06', name: '科创园-科技园北岔(绕行)', startPercent: 20, widthPercent: 3, occupancy: 'FREE', speedLimitKph: 54 },
  { id: 'T07', name: '科技园北岔-广场北岔(绕行)', startPercent: 23, widthPercent: 16, occupancy: 'FREE', speedLimitKph: 65 },
  { id: 'T08', name: '广场北岔-金融城北岔(绕行)', startPercent: 39, widthPercent: 16, occupancy: 'FREE', speedLimitKph: 65 },
  { id: 'T09', name: '金融城北岔-会展北(绕行)', startPercent: 55, widthPercent: 19, occupancy: 'FREE', speedLimitKph: 65 },
  { id: 'T10', name: '会展北-会展中心(绕行)', startPercent: 74, widthPercent: 6, occupancy: 'FREE', speedLimitKph: 54 },
  // 折返线+支线 (2500-4260m与正线平行)
  { id: 'T13', name: '中央公园-折返线西', startPercent: 40, widthPercent: 8, occupancy: 'FREE', speedLimitKph: 54 },
  { id: 'T14', name: '折返线西-折返线东', startPercent: 48, widthPercent: 9, occupancy: 'FREE', speedLimitKph: 54 },
  { id: 'T15', name: '折返线东-北城', startPercent: 57, widthPercent: 3, occupancy: 'FREE', speedLimitKph: 54 },
  { id: 'T16', name: '折返线东-支线口', startPercent: 57, widthPercent: 11, occupancy: 'FREE', speedLimitKph: 54 }
])

const powerSections = ref<PowerSectionState[]>([
  { id: 'P01', name: '南段供电(0-2500m)', startPercent: 2, widthPercent: 36, status: 'ENERGIZED', affectedTrains: [] },
  { id: 'P02', name: '中段供电(2500-5000m)', startPercent: 38, widthPercent: 38, status: 'ENERGIZED', affectedTrains: [] },
  { id: 'P03', name: '北段供电(5000-6250m)', startPercent: 76, widthPercent: 20, status: 'ENERGIZED', affectedTrains: [] }
])

const topologyNodes: TopologyNode[] = [
  { id: 'SHN', label: '上京南站', type: 'station', x: 8, y: 54, importance: 3, stationName: '上京南站' },
  { id: 'TECH', label: '科创园站', type: 'station', x: 24, y: 54, importance: 3, stationName: '科创园站' },
  { id: 'RENMIN', label: '中央公园站', type: 'station', x: 42, y: 54, importance: 3, stationName: '中央公园站' },
  { id: 'FIN', label: '北城站', type: 'station', x: 58, y: 50, importance: 3, stationName: '北城站' },
  { id: 'EXPO', label: '会展中心', type: 'station', x: 76, y: 54, importance: 3, stationName: '会展中心' },
  { id: 'AIR', label: '上京北站', type: 'station', x: 92, y: 54, importance: 3, stationName: '上京北站' },
  { id: 'DEPOT_A', label: '车辆段', type: 'depot', x: 12, y: 32, importance: 2 },
  { id: 'DEPOT_B', label: '试车线', type: 'depot', x: 7, y: 22, importance: 1 },
  { id: 'TECH_N', label: '科技园北岔', type: 'switch', x: 24, y: 32, importance: 2 },
  { id: 'RENMIN_N', label: '广场北岔', type: 'switch', x: 42, y: 30, importance: 2 },
  { id: 'FIN_N', label: '金融城北岔', type: 'switch', x: 58, y: 29, importance: 2 },
  { id: 'EXPO_N', label: '会展北联络', type: 'junction', x: 76, y: 32, importance: 1 },
  { id: 'LOOP_W', label: '折返线西', type: 'switch', x: 42, y: 78, importance: 2 },
  { id: 'LOOP_E', label: '折返线东', type: 'switch', x: 58, y: 78, importance: 2 },
  { id: 'BRANCH', label: '支线口', type: 'switch', x: 68, y: 70, importance: 1 },
]

const topologyEdges: TopologyEdge[] = [
  { id: 'E01', source: 'SHN', target: 'TECH', segmentId: 'T01', lengthMeters: 1250, speedLimitKph: 72, status: 'FREE', powerSectionId: 'P01', routeIds: ['R01'], detailCount: 1 },
  { id: 'E02', source: 'TECH', target: 'RENMIN', segmentId: 'T02', lengthMeters: 1250, speedLimitKph: 80, status: 'FREE', powerSectionId: 'P01', routeIds: ['R01'], detailCount: 1 },
  { id: 'E03', source: 'RENMIN', target: 'FIN', segmentId: 'T03', lengthMeters: 1250, speedLimitKph: 80, status: 'FREE', powerSectionId: 'P02', routeIds: ['R01'], detailCount: 1 },
  { id: 'E04', source: 'FIN', target: 'EXPO', segmentId: 'T04', lengthMeters: 1250, speedLimitKph: 72, status: 'FREE', powerSectionId: 'P02', routeIds: ['R01'], detailCount: 1 },
  { id: 'E05', source: 'EXPO', target: 'AIR', segmentId: 'T05', lengthMeters: 1250, speedLimitKph: 90, status: 'FREE', powerSectionId: 'P03', routeIds: ['R01'], detailCount: 1 },
  { id: 'E06', source: 'DEPOT_B', target: 'DEPOT_A', segmentId: 'T11', lengthMeters: 420, speedLimitKph: 25, status: 'FREE', powerSectionId: 'P01', routeIds: ['R03'], detailCount: 8 },
  { id: 'E07', source: 'DEPOT_A', target: 'TECH', segmentId: 'T12', lengthMeters: 760, speedLimitKph: 35, status: 'FREE', powerSectionId: 'P01', routeIds: ['R03'], detailCount: 14 },
  { id: 'E08', source: 'TECH', target: 'TECH_N', segmentId: 'T06', lengthMeters: 180, speedLimitKph: 35, status: 'FREE', powerSectionId: 'P01', routeIds: ['R02'], detailCount: 4 },
  { id: 'E09', source: 'TECH_N', target: 'RENMIN_N', segmentId: 'T07', lengthMeters: 980, speedLimitKph: 60, status: 'FREE', powerSectionId: 'P02', routeIds: ['R02'], detailCount: 26 },
  { id: 'E10', source: 'RENMIN_N', target: 'FIN_N', segmentId: 'T08', lengthMeters: 1040, speedLimitKph: 60, status: 'FREE', powerSectionId: 'P02', routeIds: ['R02'], detailCount: 28 },
  { id: 'E11', source: 'FIN_N', target: 'EXPO_N', segmentId: 'T09', lengthMeters: 1180, speedLimitKph: 60, status: 'FREE', powerSectionId: 'P03', routeIds: ['R02'], detailCount: 31 },
  { id: 'E12', source: 'EXPO_N', target: 'EXPO', segmentId: 'T10', lengthMeters: 260, speedLimitKph: 35, status: 'FREE', powerSectionId: 'P03', routeIds: ['R02'], detailCount: 5 },
  { id: 'E13', source: 'RENMIN', target: 'LOOP_W', segmentId: 'T13', lengthMeters: 520, speedLimitKph: 35, status: 'OCCUPIED', powerSectionId: 'P02', routeIds: ['R03'], detailCount: 11 },
  { id: 'E14', source: 'LOOP_W', target: 'LOOP_E', segmentId: 'T14', lengthMeters: 960, speedLimitKph: 45, status: 'FREE', powerSectionId: 'P02', routeIds: ['R03'], detailCount: 20 },
  { id: 'E15', source: 'LOOP_E', target: 'FIN', segmentId: 'T15', lengthMeters: 480, speedLimitKph: 35, status: 'FREE', powerSectionId: 'P03', routeIds: ['R03'], detailCount: 8 },
  { id: 'E16', source: 'LOOP_E', target: 'BRANCH', segmentId: 'T16', lengthMeters: 720, speedLimitKph: 45, status: 'FREE', powerSectionId: 'P03', routeIds: ['R03'], detailCount: 16 },
]

const topologyRoutes: TopologyRoute[] = [
  { id: 'R01', name: '正线进路', segmentIds: ['E01', 'E02', 'E03', 'E04', 'E05'], description: '上京南 → 科创园 → 中央公园 → 北城 → 会展中心 → 上京北' },
  { id: 'R02', name: '北侧绕行', segmentIds: ['E08', 'E09', 'E10', 'E11', 'E12'], description: '科技园北岔 → 金融城北岔 → 会展北联络' },
  { id: 'R03', name: '车辆段/折返', segmentIds: ['E06', 'E07', 'E13', 'E14', 'E15', 'E16'], description: '车辆段出入线与折返线' }
]

const topologySignals: TopologySignal[] = [
  { id: 'SIG-TECH', edgeId: 'E01', ratio: 0.82, status: 'green' },
  { id: 'SIG-RENMIN', edgeId: 'E02', ratio: 0.78, status: 'yellow' },
  { id: 'SIG-FIN', edgeId: 'E03', ratio: 0.74, status: 'red' },
  { id: 'SIG-EXPO', edgeId: 'E05', ratio: 0.25, status: 'green' },
  { id: 'SIG-NORTH', edgeId: 'E11', ratio: 0.15, status: 'red' }
]

const topologyEdgeByTrackSegment: Record<string, string> = {
  T01: 'E01', T02: 'E02', T03: 'E03', T04: 'E04', T05: 'E05',
  T06: 'E08', T07: 'E09', T08: 'E10', T09: 'E11', T10: 'E12',
  T11: 'E06', T12: 'E07',
  T13: 'E13', T14: 'E14', T15: 'E15', T16: 'E16'
}

const trainTopologyAnchors: Record<string, { edgeId: string; ratio: number }> = {
  T101: { edgeId: 'E01', ratio: 0.5 },
  T203: { edgeId: 'E02', ratio: 0.58 },
  T305: { edgeId: 'E03', ratio: 0.55 },
  T407: { edgeId: 'E04', ratio: 0.62 },
  T509: { edgeId: 'E05', ratio: 0.84 }
}

const passengerStations = ref<StationPassengerState[]>([
  { name: '上京南站', inbound: 816, outbound: 642, waiting: 1320, capacity: 2600, forecastRate: 58 },
  { name: '科创园站', inbound: 1320, outbound: 930, waiting: 2180, capacity: 2800, forecastRate: 82 },
  { name: '中央公园站', inbound: 2260, outbound: 1640, waiting: 3560, capacity: 3200, forecastRate: 111 },
  { name: '北城站', inbound: 1740, outbound: 1480, waiting: 2460, capacity: 3000, forecastRate: 88 },
  { name: '会展中心', inbound: 980, outbound: 720, waiting: 1160, capacity: 2400, forecastRate: 54 },
  { name: '上京北站', inbound: 660, outbound: 760, waiting: 860, capacity: 2200, forecastRate: 42 }
])

const alarms = ref<UnifiedAlarm[]>([
  { id: 'A001', time: '08:34:55', source: '车辆', location: 'T305', level: 3, description: '整列满载率超过100%，车辆故障码 VHL-23', impact: '影响后续 T407，预计影响旅客 1280 人', confirmed: false, muted: false },
  { id: 'A002', time: '08:34:12', source: '轨道信号', location: '中央公园-北城', level: 3, description: '轨道电路红光带，列车接近故障区段', impact: '闭塞区段 T03，影响列车 T305/T407', confirmed: false, muted: false },
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
let trendChartResizeObserver: ResizeObserver | null = null
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
const selectedTopologyRoute = computed<TopologyRoute>(() => topologyRoutes.find((route) => route.id === selectedRouteId.value) ?? topologyRoutes[0]!)
const topologyNodeById = computed(() => new Map(topologyNodes.map((node) => [node.id, node])))

const backendTopologyEdges = computed<TopologyEdge[]>(() => {
  if (!backendConnected.value || trackSegments.value.length === 0) return topologyEdges
  const segById = new Map(trackSegments.value.map(s => [s.id, s]))
  return topologyEdges.map((edge) => {
    const segment = segById.get(edge.segmentId)
    if (!segment) return edge
    return {
      ...edge,
      status: segment.occupancy,
      speedLimitKph: segment.speedLimitKph,
      detailCount: Math.max(1, Math.round(segment.widthPercent))
    }
  })
})

const visibleTopologyEdges = computed(() => {
  const sourceEdges = backendTopologyEdges.value
  if (topologyView.value === 'route') {
    const routeEdgeIds = new Set(selectedTopologyRoute.value.segmentIds)
    return sourceEdges.filter((edge) => routeEdgeIds.has(edge.id) || edge.routeIds.includes('R01'))
  }

  if (topologyView.value === 'station') {
    return sourceEdges.filter((edge) => {
      const source = topologyNodeById.value.get(edge.source)
      const target = topologyNodeById.value.get(edge.target)
      return [source?.x, target?.x].some((x) => x !== undefined && x >= 22 && x <= 78)
    })
  }

  // 后端已连接时显示全部边（均有真实段对应），离线时保留原过滤
  return backendConnected.value ? sourceEdges : sourceEdges.filter((edge) => edge.detailCount >= 10 || edge.routeIds.includes('R01'))
})
const visibleTopologyEdgeIds = computed(() => new Set(visibleTopologyEdges.value.map((edge) => edge.id)))

const visibleTopologyNodes = computed(() => {
  const visibleIds = new Set<string>()
  visibleTopologyEdges.value.forEach((edge) => {
    visibleIds.add(edge.source)
    visibleIds.add(edge.target)
  })
  return topologyNodes.filter((node) => visibleIds.has(node.id) || node.importance >= 3)
})

const topologyTrainMarkers = computed(() =>
  trains.value
    .map((train) => {
      const anchor = resolveTrainTopologyAnchor(train)
      if (!anchor || !visibleTopologyEdgeIds.value.has(anchor.edgeId)) return null
      return { ...train, ...topologyEdgePoint(anchor.edgeId, anchor.ratio) }
    })
    .filter((train): train is TrainMonitorState & { x: number; y: number } => train !== null)
)

const visibleTopologySignals = computed(() =>
  topologySignals
    .map((signal) => {
      if (!visibleTopologyEdgeIds.value.has(signal.edgeId)) return null
      return { ...signal, ...topologyEdgePoint(signal.edgeId, signal.ratio) }
    })
    .filter((signal): signal is TopologySignal & TopologyTrackPoint => signal !== null)
)

const collapsedSegmentCount = computed(() => visibleTopologyEdges.value.reduce((sum, edge) => sum + edge.detailCount, 0))

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

function topologyNodeStyle(node: TopologyNode): Record<string, string> {
  return { left: `${node.x}%`, top: `${node.y}%` }
}

function topologyEdgeLine(edge: TopologyEdge): Record<string, number> {
  const source = topologyNodeById.value.get(edge.source)
  const target = topologyNodeById.value.get(edge.target)
  return {
    x1: source?.x ?? 0,
    y1: source?.y ?? 0,
    x2: target?.x ?? 0,
    y2: target?.y ?? 0
  }
}

function clampRatio(value: number): number {
  if (!Number.isFinite(value)) return 0
  return Math.min(Math.max(value, 0), 1)
}

function resolveTrainTopologyAnchor(train: TrainMonitorState): { edgeId: string; ratio: number } | null {
  const trackSegment = trackSegments.value.find(
    (segment) => train.positionPercent >= segment.startPercent && train.positionPercent <= segment.startPercent + segment.widthPercent
  )
  if (trackSegment) {
    const edgeId = topologyEdgeByTrackSegment[trackSegment.id]
    if (edgeId) {
      const ratio = (train.positionPercent - trackSegment.startPercent) / trackSegment.widthPercent
      return { edgeId, ratio: clampRatio(ratio) }
    }
  }

  return trainTopologyAnchors[train.id] ?? null
}

function topologyEdgePoint(edgeId: string, ratio: number): TopologyTrackPoint {
  const edge = topologyEdges.find((item) => item.id === edgeId)
  if (!edge) return { x: 0, y: 0 }

  const line = topologyEdgeLine(edge)
  const normalizedRatio = clampRatio(ratio)
  return {
    x: line.x1 + (line.x2 - line.x1) * normalizedRatio,
    y: line.y1 + (line.y2 - line.y1) * normalizedRatio
  }
}

function topologyEdgeLabelStyle(edge: TopologyEdge): Record<string, string> {
  const line = topologyEdgeLine(edge)
  return {
    left: `${(line.x1 + line.x2) / 2}%`,
    top: `${(line.y1 + line.y2) / 2}%`
  }
}

function topologyEdgeClass(edge: TopologyEdge): Array<string | Record<string, boolean>> {
  return [
    'topology-edge',
    activeLayers.value.track ? edge.status.toLowerCase() : 'muted',
    {
      selected: topologyView.value === 'route' && selectedTopologyRoute.value.segmentIds.includes(edge.id),
      dimmed: topologyView.value === 'route' && !selectedTopologyRoute.value.segmentIds.includes(edge.id)
    }
  ]
}

function topologyNodeClass(node: TopologyNode): Array<string | Record<string, boolean>> {
  const station = node.stationName ? passengerStations.value.find((item) => item.name === node.stationName) : null
  return [
    'topology-node',
    `node-${node.type}`,
    activeLayers.value.passengers && station ? stationHeatClass(station) : 'neutral',
    { selected: node.stationName === selectedLocation.value }
  ]
}

function selectTopologyNode(node: TopologyNode): void {
  if (node.stationName) selectedLocation.value = node.stationName
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

function mapTrackOccupancy(occupancy: string): TrackSegmentState['occupancy'] {
  if (occupancy === 'OCCUPIED' || occupancy === 'RESERVED') return 'OCCUPIED'
  if (occupancy === 'FAULT' || occupancy === 'BLOCKED') return 'FAULT'
  return 'FREE'
}

function applyBackendSnapshot(snapshot: SimulationSnapshot): void {
  backendConnected.value = true
  backendErrorMessage.value = ''
  backendTick.value = snapshot.tick
  backendStatus.value = snapshot.status
  simulationClock.value = new Date(snapshot.simulatedTime).toLocaleTimeString('zh-CN', { hour12: false })

  const maxPositionMeters = Math.max(1, ...snapshot.trackSegments.map((segment) => segment.endMeters), ...snapshot.trains.map((train) => train.positionMeters))

  // 性能策略：后端快照按模块整体替换，避免深层逐字段监听带来的额外渲染开销，复杂度 O(n)。
  trains.value = snapshot.trains.map((train) => ({
    id: train.id,
    serviceNo: train.serviceNo || train.id,
    positionPercent: Math.min(95, Math.max(5, (train.positionMeters / maxPositionMeters) * 100)),
    speedKph: Math.round(train.speedMetersPerSecond * 3.6),
    loadRate: Math.round((train.loadRate ?? 0) * 100),
    faultCode: train.faultCode || '',
    section: train.currentStationId || `里程 ${Math.round(train.positionMeters)}m`
  }))

  trackSegments.value = snapshot.trackSegments.map((segment) => ({
    id: segment.id,
    name: `${segment.fromNode}-${segment.toNode}`,
    startPercent: Math.min(95, Math.max(4, (segment.startMeters / maxPositionMeters) * 100)),
    widthPercent: Math.max(3, ((segment.endMeters - segment.startMeters) / maxPositionMeters) * 100),
    occupancy: mapTrackOccupancy(segment.occupancy),
    speedLimitKph: Math.round(segment.speedLimitMetersPerSecond * 3.6)
  }))

  powerSections.value = snapshot.powerSections.map((section) => ({
    id: section.id,
    name: section.name,
    startPercent: Math.min(95, Math.max(4, (section.startMeters / maxPositionMeters) * 100)),
    widthPercent: Math.max(4, ((section.endMeters - section.startMeters) / maxPositionMeters) * 100),
    status: section.status === 'ENERGIZED' ? 'ENERGIZED' : section.status === 'LOST' ? 'LOST' : 'OVERRANGE',
    affectedTrains: section.affectedTrainIds ?? []
  }))

  alarms.value = snapshot.alarms.map((alarm) => ({
    id: alarm.id,
    time: new Date(alarm.raisedAt).toLocaleTimeString('zh-CN', { hour12: false }),
    source: alarm.sourceModule,
    location: alarm.locationRef,
    level: Math.min(Math.max(alarm.level, 1), 3) as AlarmLevel,
    description: alarm.title,
    impact: alarm.detail,
    confirmed: alarm.confirmed,
    muted: false
  }))

  dispatchCommands.value = snapshot.dispatch.activeCommands.map((command) => ({
    id: command.id,
    type: command.commandType,
    target: command.trainId,
    status: command.status === 'APPLIED' ? '已执行' : command.status === 'PENDING' ? '待执行' : '异常',
    deviation: command.reason
  }))

  linkStates.value = [
    { name: '后端仿真', status: '在线', latencyMs: 0, lastPacket: simulationClock.value },
    { name: '车辆运行时', status: snapshot.vehicleRuntime.heartbeatStatus === 'UP' ? '在线' : '延迟', latencyMs: snapshot.vehicleRuntime.latencyMillis, lastPacket: simulationClock.value },
    { name: 'WebSocket', status: '在线', latencyMs: 0, lastPacket: simulationClock.value },
    { name: '调度模块', status: snapshot.dispatch.interventionActive ? '延迟' : '在线', latencyMs: 0, lastPacket: simulationClock.value }
  ]
}

function tickMockData(): void {
  if (backendConnected.value) return
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

async function loadBackendSnapshot(): Promise<void> {
  try {
    applyBackendSnapshot(await simulationApi.snapshot())
  } catch (error) {
    backendConnected.value = false
    backendErrorMessage.value = error instanceof Error ? error.message : '后端快照加载失败'
  }
}

async function runSimulationTick(): Promise<void> {
  if (backendStatus.value !== 'RUNNING') {
    try { backendStatus.value = (await simulationApi.start()).status } catch { return }
  }
  try { applyBackendSnapshot(await simulationApi.tick()) } catch { /* WS covers */ }
}

function resizeTrendChart(): void {
  // 性能策略：仅在容器尺寸变化时触发 ECharts resize，避免大屏缩放时 canvas 使用旧宽度溢出 section。
  trendChart?.resize()
}

function renderTrendChart(): void {
  if (!trendChartRef.value) return
  if (!trendChart) trendChart = echarts.init(trendChartRef.value)
  resizeTrendChart()
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
  void loadBackendSnapshot()
  simulationSocket.connect()
  simulationSocket.subscribe(applyBackendSnapshot)
  clockTimer = window.setInterval(() => {
    if (!backendConnected.value) {
      simulationClock.value = new Date().toLocaleTimeString('zh-CN', { hour12: false })
    }
  }, 1000)
  dataTimer = window.setInterval(() => {
    if (backendConnected.value) {
      void runSimulationTick()
    } else {
      tickMockData()
    }
  }, 2000)
  trendChartResizeObserver = new ResizeObserver(resizeTrendChart)
  if (trendChartRef.value) trendChartResizeObserver.observe(trendChartRef.value)
  window.addEventListener('resize', resizeTrendChart)
})

onBeforeUnmount(() => {
  window.clearInterval(clockTimer)
  window.clearInterval(dataTimer)
  window.removeEventListener('resize', resizeTrendChart)
  trendChartResizeObserver?.disconnect()
  trendChartResizeObserver = null
  simulationSocket.disconnect()
  trendChart?.dispose()
})
</script>

<template>
  <DispatchLoopDebugView v-if="activePage === 'dispatchLoop'" @back="activePage = 'monitor'" />
  <main v-else class="monitor-shell">
    <header class="topbar">
      <section>
        <p class="eyebrow">Railway-Sim 综合监控中心</p>
        <h1>上京地铁运营态势监控</h1>
      </section>
      <section class="topbar-actions" aria-label="仿真状态">
        <span :class="['status-pill', { running: backendConnected }]">
          {{ backendConnected ? `后端已连接 · ${backendStatus} · Tick ${backendTick}` : '本地演示数据' }}
        </span>
        <span v-if="backendErrorMessage" class="backend-error">{{ backendErrorMessage }}</span>
        <span>{{ simulationClock }}</span>
        <button type="button" :class="['sound-button', { off: !soundEnabled }]" @click="soundEnabled = !soundEnabled">
          {{ soundEnabled ? '声警开启' : '声警关闭' }}
        </button>
        <button type="button" class="debug-entry-button" @click="activePage = 'dispatchLoop'">
          调度闭环
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

        <div class="topology-controls">
          <div class="segmented">
            <button type="button" :class="{ active: topologyView === 'overview' }" @click="topologyView = 'overview'">总览</button>
            <button type="button" :class="{ active: topologyView === 'station' }" @click="topologyView = 'station'">局部</button>
            <button type="button" :class="{ active: topologyView === 'route' }" @click="topologyView = 'route'">进路</button>
          </div>
          <select v-model="selectedRouteId" aria-label="进路选择">
            <option v-for="route in topologyRoutes" :key="route.id" :value="route.id">{{ route.name }}</option>
          </select>
          <span>{{ visibleTopologyNodes.length }} 节点 / {{ visibleTopologyEdges.length }} 聚合边 / {{ collapsedSegmentCount }} Seg</span>
        </div>

        <div class="line-map topology-map">
          <div v-if="activeLayers.power" class="power-band topology-power-band">
            <span
              v-for="section in powerSections"
              :key="section.id"
              :class="['power-section', section.status.toLowerCase()]"
              :style="{ left: `${section.startPercent}%`, width: `${section.widthPercent}%` }"
            >{{ section.name }}</span>
          </div>

          <svg class="topology-svg" viewBox="0 0 100 100" preserveAspectRatio="none" aria-hidden="true">
            <line
              v-for="edge in visibleTopologyEdges"
              :key="edge.id"
              v-bind="topologyEdgeLine(edge)"
              :class="topologyEdgeClass(edge)"
            />
          </svg>

          <span
            v-for="edge in visibleTopologyEdges"
            :key="`${edge.id}-label`"
            class="topology-edge-label"
            :style="topologyEdgeLabelStyle(edge)"
          >
            {{ edge.detailCount }} Seg
          </span>

          <button
            v-for="node in visibleTopologyNodes"
            :key="node.id"
            type="button"
            :class="topologyNodeClass(node)"
            :style="topologyNodeStyle(node)"
            @click="selectTopologyNode(node)"
          >
            <span class="node-dot"></span>
            <span class="node-label">{{ node.label }}</span>
          </button>

          <div v-if="activeLayers.signals" class="signal-layer">
            <span
              v-for="signal in visibleTopologySignals"
              :key="signal.id"
              :class="['topology-signal', signal.status, { blink: signal.status === 'red' }]"
              :style="{ left: `${signal.x}%`, top: `${signal.y}%` }"
            ></span>
          </div>

          <div v-if="activeLayers.trains" class="train-layer">
            <button
              v-for="train in topologyTrainMarkers"
              :key="train.id"
              type="button"
              :class="['train-marker topology-train', loadClass(train.loadRate), { fault: train.faultCode }]"
              :style="{ left: `${train.x}%`, top: `${train.y}%` }"
              :title="`${train.serviceNo} ${train.speedKph}km/h 满载率${train.loadRate}%`"
            >
              <img :src="topologyTrainUrl" alt="" class="train-sprite" aria-hidden="true" />
              <span class="train-code">{{ train.serviceNo }} {{ train.speedKph }}km/h</span>
            </button>
          </div>
        </div>

        <div class="map-details">
          <article>
            <h3>{{ selectedStation.name }}</h3>
            <p>候车 {{ selectedStation.waiting }} 人 / 容量 {{ selectedStation.capacity }} 人</p>
            <div class="progress"><span :class="stationHeatClass(selectedStation)" :style="{ width: `${Math.min(selectedStationLoadRate, 100)}%` }"></span></div>
          </article>
          <article>
            <h3>{{ selectedTopologyRoute.name }}</h3>
            <p>{{ selectedTopologyRoute.description }}，当前高亮 {{ selectedTopologyRoute.segmentIds.length }} 条聚合边。</p>
          </article>
          <article>
            <h3>调度动作</h3>
            <p>临时限速 SEG-113~167 已执行，G203-G305 间隔调整待执行；牵引三区失电影响会展中心断面。</p>
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

.debug-entry-button {
  border: 1px solid #2563eb;
  border-radius: 8px;
  background: #2563eb;
  color: #fff;
  min-height: 34px;
  padding: 7px 12px;
  font-size: 13px;
  font-weight: 700;
  cursor: pointer;
}

.debug-entry-button:hover {
  background: #1d4ed8;
  border-color: #1d4ed8;
}
</style>
