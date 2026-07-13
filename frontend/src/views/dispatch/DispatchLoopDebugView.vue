<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { dispatchApi } from '../../api/dispatch'
import { useSimulation } from '../../composables/useSimulation'
import RunPlanPanel from '../../components/dispatch/RunPlanPanel.vue'
import StationHeadwayPanel from '../../components/dispatch/StationHeadwayPanel.vue'
import RouteClosurePanel from '../../components/dispatch/RouteClosurePanel.vue'
import type {
  DispatchDisturbance,
  DispatchRouteInfo,
  OperationPlanView,
  OperationRouteTemplate
} from '../../types/dispatch'
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
  refreshSimulationSnapshot,
} = useSimulation()

const trains = computed(() => snapshot.value?.trains ?? [])
const authorities = computed(() => snapshot.value?.authorities ?? [])
const authorityByTrain = computed(() => new Map(authorities.value.map((authority) => [authority.trainId, authority])))
const routes = computed(() => snapshot.value?.routeStates ?? [])
const switches = computed(() => snapshot.value?.switchStates ?? [])
const signals = computed(() => snapshot.value?.signalStates ?? [])
const routeDecisions = computed(() => dispatch.value.routeDecisions ?? [])
const routeReservations = computed(() => dispatch.value.routeReservations ?? [])
const lineRegulationPlan = computed(() => dispatch.value.lineRegulationPlan)

const activeTrainCount = computed(() => trains.value.length)
const dwellingTrainCount = computed(() => trains.value.filter((train) => train.status === 'DWELLING').length)
const activeCommandCount = computed(() => dispatch.value.activeCommands.length)
const openDisturbanceCount = computed(() => dispatch.value.openDisturbances.length)
const demoSpeedLimitMps = ref(6)
const demoShortHeadwaySec = ref(180)
const demoLongHeadwaySec = ref(540)
const selectedTrainId = ref('')
const manualToolsOpen = ref(false)
const manualActionPending = ref(false)
const manualActionMessage = ref('')
const comparisonBaseline = ref<ComparisonBaseline | null>(null)
const dispatchRouteList = ref<DispatchRouteInfo[]>([])
const operationRouteTemplates = ref<OperationRouteTemplate[]>([])
const selectedRouteId = ref('')
const selectedRouteTrainId = ref('')
const routeOperationMessage = ref('')
const routeRequestPending = ref(false)
const headwayShrinkRatio = 0.7
const headwayExpandRatio = 1.5

type DispatchLineNodeType = 'station' | 'switch' | 'junction' | 'depot'
type DispatchLineEdgeState = 'FREE' | 'OCCUPIED' | 'FAULT' | 'REQUESTED' | 'ACCEPTED' | 'REJECTED'

interface DispatchLineNode {
  id: string
  label: string
  type: DispatchLineNodeType
  x: number
  y: number
  stationId?: string
}

interface DispatchLineEdge {
  id: string
  source: string
  target: string
  segmentId: string
  label: string
}

type DispatchLineVisibleEdge = DispatchLineEdge & {
  routeIds: string[]
  state: DispatchLineEdgeState
  routeStatus: string | null
  reservationState: string | null
}

interface DispatchLineTrainMarker {
  id: string
  x: number
  y: number
  speedMps: number
  selected: boolean
}

interface RoutePlannerCandidate {
  key: string
  routeId: string
  routeName: string
  direction: 'UP' | 'DOWN'
  pointIds: string[]
  nodeIds: string[]
  stationIds: string[]
  segmentIds: string[]
}

const dispatchLineNodes: DispatchLineNode[] = [
  { id: 'U_START', label: '上行起点', type: 'junction', x: 4, y: 58 },
  { id: 'S101', label: '郭公庄站', type: 'station', x: 7, y: 58, stationId: 'S101' },
  { id: 'S102', label: '丰台科技园站', type: 'station', x: 14, y: 58, stationId: 'S102' },
  { id: 'S103', label: '科怡路站', type: 'station', x: 21, y: 58, stationId: 'S103' },
  { id: 'S104', label: '丰台南路站', type: 'station', x: 28, y: 58, stationId: 'S104' },
  { id: 'S105', label: '丰台东大街站', type: 'station', x: 35, y: 58, stationId: 'S105' },
  { id: 'S106', label: '七里庄站', type: 'station', x: 42, y: 58, stationId: 'S106' },
  { id: 'S107', label: '六里桥站', type: 'station', x: 49, y: 58, stationId: 'S107' },
  { id: 'S108', label: '六里桥东站', type: 'station', x: 56, y: 58, stationId: 'S108' },
  { id: 'S109', label: '北京西站', type: 'station', x: 63, y: 58, stationId: 'S109' },
  { id: 'S110', label: '军事博物馆站', type: 'station', x: 70, y: 58, stationId: 'S110' },
  { id: 'S111', label: '白堆子站', type: 'station', x: 77, y: 58, stationId: 'S111' },
  { id: 'S112', label: '白石桥南站', type: 'station', x: 84, y: 58, stationId: 'S112' },
  { id: 'S113', label: '国家图书馆站', type: 'station', x: 91, y: 58, stationId: 'S113' },
  { id: 'D_START', label: '下行起点', type: 'junction', x: 96, y: 58 },
  { id: 'TB_GGZ', label: '郭公庄折返', type: 'switch', x: 7, y: 74 },
  { id: 'TB_LIB', label: '国图折返', type: 'switch', x: 91, y: 42 },
]

const dispatchLineEdges: DispatchLineEdge[] = [
  { id: 'EU02', source: 'U_START', target: 'S101', segmentId: 'U02', label: 'U02' },
  { id: 'EU10', source: 'S101', target: 'S102', segmentId: 'U10', label: 'U10' },
  { id: 'EU13', source: 'S102', target: 'S103', segmentId: 'U13', label: 'U13' },
  { id: 'EU16', source: 'S103', target: 'S104', segmentId: 'U16', label: 'U16' },
  { id: 'EU19', source: 'S104', target: 'S105', segmentId: 'U19', label: 'U19' },
  { id: 'EU20', source: 'S105', target: 'S106', segmentId: 'U20', label: 'U20' },
  { id: 'EU23', source: 'S106', target: 'S107', segmentId: 'U23', label: 'U23' },
  { id: 'EU27', source: 'S107', target: 'S108', segmentId: 'U27', label: 'U27' },
  { id: 'EU34', source: 'S108', target: 'S109', segmentId: 'U34', label: 'U34' },
  { id: 'EU38', source: 'S109', target: 'S110', segmentId: 'U38', label: 'U38' },
  { id: 'EU42', source: 'S110', target: 'S111', segmentId: 'U42', label: 'U42' },
  { id: 'EU46', source: 'S111', target: 'S112', segmentId: 'U46', label: 'U46' },
  { id: 'EU48', source: 'S112', target: 'S113', segmentId: 'U48', label: 'U48' },
  { id: 'ED47', source: 'D_START', target: 'S113', segmentId: 'D47', label: 'D47' },
  { id: 'ED43', source: 'S113', target: 'S112', segmentId: 'D43', label: 'D43' },
  { id: 'ED36', source: 'S112', target: 'S111', segmentId: 'D36', label: 'D36' },
  { id: 'ED28', source: 'S111', target: 'S110', segmentId: 'D28', label: 'D28' },
  { id: 'ED24', source: 'S110', target: 'S109', segmentId: 'D24', label: 'D24' },
  { id: 'ED21', source: 'S109', target: 'S108', segmentId: 'D21', label: 'D21' },
  { id: 'ED17', source: 'S108', target: 'S107', segmentId: 'D17', label: 'D17' },
  { id: 'ED14', source: 'S107', target: 'S106', segmentId: 'D14', label: 'D14' },
  { id: 'ED11', source: 'S106', target: 'S105', segmentId: 'D11', label: 'D11' },
  { id: 'ED07', source: 'S105', target: 'S104', segmentId: 'D07', label: 'D07' },
  { id: 'ED04', source: 'S104', target: 'S103', segmentId: 'D04', label: 'D04' },
  { id: 'ED03', source: 'S103', target: 'S102', segmentId: 'D03', label: 'D03' },
  { id: 'EXGGZ', source: 'S101', target: 'U_START', segmentId: 'XGGZ', label: 'XGGZ' },
  { id: 'EXLIB', source: 'S113', target: 'D_START', segmentId: 'XLIB', label: 'XLIB' },
]

const routePlannerOpen = ref(false)
const routePlannerNodeIds = ref<string[]>([])
const routePlannerCandidateKey = ref('')
const routePlannerLeadSeconds = ref(60)
const routePlannerHeadwaySeconds = ref(300)
const routePlannerMessage = ref('')
const selectedOperationPlanId = ref('')

const dispatchLineNodeById = computed(() => new Map(dispatchLineNodes.map((node) => [node.id, node])))
const dispatchLineNodeByPointId = computed(() => {
  const entries: Array<[string, DispatchLineNode]> = []
  for (const node of dispatchLineNodes) {
    entries.push([node.id, node])
    if (node.stationId) entries.push([node.stationId, node])
  }
  return new Map(entries)
})
const dispatchLineEdgeBySegmentId = computed(() => new Map(dispatchLineEdges.map((edge) => [edge.segmentId, edge])))
const routeInfoById = computed(() => new Map(dispatchRouteList.value.map((route) => [route.routeId, route])))
const routeSegmentsById = computed(() => {
  const entries = new Map<string, string[]>()
  for (const route of dispatchRouteList.value) {
    entries.set(route.routeId, route.segmentIds)
  }
  for (const template of operationRouteTemplates.value) {
    if (!entries.has(template.routeId) && template.segmentIds.length > 0) {
      entries.set(template.routeId, template.segmentIds)
    }
  }
  for (const route of routes.value) {
    if (!entries.has(route.routeId) && route.axleSegmentIds.length > 0) {
      entries.set(route.routeId, route.axleSegmentIds)
    }
  }
  return entries
})
const dispatchLineRouteIdsBySegmentId = computed(() => {
  const entries = new Map<string, string[]>()
  for (const edge of dispatchLineEdges) {
    const routeIds = [...routeSegmentsById.value.entries()]
      .filter(([, segmentIds]) => segmentIds.includes(edge.segmentId))
      .map(([routeId]) => routeId)
    entries.set(edge.segmentId, routeIds)
  }
  return entries
})
const dispatchLineTrackBySegmentId = computed(() =>
  new Map((snapshot.value?.trackSegments ?? []).map((segment) => [segment.id, segment]))
)
const dispatchLineRouteStateById = computed(() => new Map(routes.value.map((route) => [route.routeId, route])))
const activeDispatchRouteIds = computed(() => {
  const ids = new Set<string>()
  for (const reservation of routeReservations.value) {
    if (['REQUESTED', 'ACCEPTED', 'REJECTED', 'TIMEOUT'].includes(reservation.state)) ids.add(reservation.routeId)
  }
  for (const route of routes.value) {
    if (['VALIDATING', 'SETTING_SWITCHES', 'LOCKED', 'OCCUPIED', 'CONFLICTED', 'REJECTED'].includes(route.status)) ids.add(route.routeId)
  }
  return ids
})
const dispatchLineMappedRoutes = computed(() => {
  const ids = new Set<string>()
  for (const route of dispatchRouteList.value) ids.add(route.routeId)
  for (const route of routes.value) ids.add(route.routeId)
  return [...ids].filter((routeId) => dispatchLineEdges.some((edge) =>
    (dispatchLineRouteIdsBySegmentId.value.get(edge.segmentId) ?? []).includes(routeId)
  ))
})
const dispatchLineUnmappedRoutes = computed(() =>
  dispatchRouteList.value
    .map((route) => route.routeId)
    .filter((routeId) => !dispatchLineEdges.some((edge) =>
      (dispatchLineRouteIdsBySegmentId.value.get(edge.segmentId) ?? []).includes(routeId)
    ))
)
const dispatchLineVisibleEdges = computed<DispatchLineVisibleEdge[]>(() =>
  dispatchLineEdges.map((edge) => {
    const routeIds = dispatchLineRouteIdsBySegmentId.value.get(edge.segmentId) ?? []
    const visibleEdge: DispatchLineEdge & { routeIds: string[] } = { ...edge, routeIds }
    return {
      ...visibleEdge,
      state: dispatchLineEdgeState(visibleEdge),
      routeStatus: routeIds.map((routeId) => dispatchLineRouteStateById.value.get(routeId)?.status).find(Boolean) ?? null,
      reservationState: routeIds
        .map((routeId) => routeReservations.value.find((reservation) => reservation.routeId === routeId)?.state)
        .find(Boolean) ?? null
    }
  })
)
const selectedOperationPlan = computed(() =>
  generatedOperationPlans.value.find((item) => item.planId === selectedOperationPlanId.value) ?? null
)
const generatedOperationPlans = computed<OperationPlanView[]>(() => dispatch.value.operationPlans ?? [])
const routePlannerCandidates = computed(() => {
  return operationRouteTemplates.value
    .flatMap((template) => routeTemplateDirections(template.routeId)
      .map((direction) => routeTemplateCandidate(template, direction)))
    .filter((candidate): candidate is RoutePlannerCandidate => candidate !== null)
})
const selectedRoutePlannerCandidate = computed(() => {
  if (routePlannerCandidates.value.length === 0) return null
  return routePlannerCandidates.value.find((candidate) => candidate.key === routePlannerCandidateKey.value)
    ?? routePlannerCandidates.value[0]
    ?? null
})
const routePlannerPreviewNodeIds = computed(() =>
  routePlannerOpen.value
    ? selectedRoutePlannerCandidate.value?.nodeIds
      ?? selectedOperationPlan.value?.pointIds.map(pointIdToNodeId).filter((nodeId): nodeId is string => Boolean(nodeId))
      ?? []
    : selectedOperationPlan.value?.pointIds.map(pointIdToNodeId).filter((nodeId): nodeId is string => Boolean(nodeId)) ?? []
)
const routePlannerPreviewSegmentIds = computed(() =>
  new Set(
    routePlannerOpen.value
      ? selectedRoutePlannerCandidate.value?.segmentIds ?? selectedOperationPlan.value?.segmentIds ?? []
      : selectedOperationPlan.value?.segmentIds ?? []
  )
)
const routePlannerSelectedNodeSet = computed(() => new Set(routePlannerNodeIds.value))
const routePlannerPreviewNodeSet = computed(() => new Set(routePlannerPreviewNodeIds.value))
const routePlannerStops = computed(() =>
  (selectedRoutePlannerCandidate.value?.nodeIds ?? [])
    .map((nodeId) => dispatchLineNodeById.value.get(nodeId))
    .filter((node): node is DispatchLineNode => node !== undefined)
)
const routePlannerOriginText = computed(() => routePlannerStops.value[0]?.label ?? '-')
const routePlannerDestinationText = computed(() =>
  routePlannerStops.value.length >= 2 ? routePlannerStops.value[routePlannerStops.value.length - 1]?.label ?? '-' : '-'
)
const routePlannerViaText = computed(() => {
  const via = routePlannerStops.value.slice(1, -1).map((node) => node.label)
  return via.length > 0 ? via.join(' / ') : '-'
})
const routePlannerAutoTrain = computed(() => {
  const origin = routePlannerStops.value[0]
  if (!origin) return null
  const assignedTrainIds = new Set(generatedOperationPlans.value
    .filter((item) => ['PLANNED', 'ROUTE_REQUESTED'].includes(item.status))
    .map((item) => item.trainId))
  const ranked = trains.value
    .filter((train) => !assignedTrainIds.has(train.id) && train.faultLevel <= 1)
    .map((train) => ({
      train,
      score: trainAssignmentScore(train, origin)
    }))
    .sort((first, second) => first.score - second.score)
  return ranked[0]?.train ?? null
})
const routePlannerNextDepartureTick = computed(() =>
  tick.value
    + normalizedPlannerSeconds(routePlannerLeadSeconds.value, 0, 3600)
    + generatedOperationPlans.value.filter((item) => item.status === 'PLANNED').length * normalizedPlannerSeconds(routePlannerHeadwaySeconds.value, 30, 3600)
)
const canCreateOperationPlan = computed(() =>
  operationRouteTemplates.value.length > 0
    && selectedRoutePlannerCandidate.value !== null
    && routePlannerAutoTrain.value !== null
)
const dispatchLineTrainMarkers = computed<DispatchLineTrainMarker[]>(() =>
  trains.value
    .map((train) => {
      const authority = authorityByTrain.value.get(train.id)
      const authorityEdge = dispatchLineEdgeBySegmentId.value.get(authority?.currentSegmentId || '') ?? null
      const fallbackEdge = authorityEdge ?? dispatchLineEdges.find((item) => {
        const segment = dispatchLineTrackBySegmentId.value.get(item.segmentId)
        return segment && train.positionMeters >= segment.startMeters && train.positionMeters <= segment.endMeters
      })
      if (!fallbackEdge) return null

      const segment = dispatchLineTrackBySegmentId.value.get(fallbackEdge.segmentId)
      const ratio = segment
        ? clamp((train.positionMeters - segment.startMeters) / Math.max(1, segment.endMeters - segment.startMeters), 0, 1)
        : 0.5
      const point = dispatchLineEdgePoint(fallbackEdge, ratio)
      return {
        id: train.id,
        x: point.x,
        y: point.y,
        speedMps: train.speedMetersPerSecond,
        selected: train.id === selectedDemoTrain.value?.id
      }
    })
    .filter((marker): marker is DispatchLineTrainMarker => marker !== null)
)
const dispatchLineSummary = computed(() =>
  `${dispatchLineNodes.length} 节点 / ${dispatchLineEdges.length} 聚合边 / ${dispatchLineMappedRoutes.value.length} 条已映射进路`
)

const orderedTrains = computed(() =>
  [...trains.value].sort((firstTrain, secondTrain) => firstTrain.positionMeters - secondTrain.positionMeters)
)
const rearTrain = computed(() => orderedTrains.value[0] ?? null)
const frontTrain = computed(() => orderedTrains.value[1] ?? null)
const selectedDemoTrain = computed(() =>
  selectedTrainId.value ? trains.value.find((train) => train.id === selectedTrainId.value) ?? null : null
)
const selectedFrontTrain = computed(() => {
  const target = selectedDemoTrain.value
  if (!target) return null
  return orderedTrains.value.find((train) => train.positionMeters > target.positionMeters && train.id !== target.id) ?? null
})
const primaryHeadwayProfile = computed(() => {
  if (selectedDemoTrain.value) {
    const selectedProfile = dispatch.value.trainProfiles.find((profile) => profile.trainId === selectedDemoTrain.value?.id)
    if (selectedProfile) return selectedProfile
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
      hint: '间隔偏差为负，直接作用本车：延长停站或降低运行节奏'
    }
  }
  if (actual > headwayTooLongLimitSec.value) {
    return {
      state: 'TOO_LONG',
      label: '过长',
      value: actual - headwayTooLongLimitSec.value,
      hint: '间隔偏差为正，直接作用本车：缩短停站或请求追赶运行'
    }
  }
  return {
    state: 'ON_TARGET',
    label: '正常',
    value: 0,
    hint: '实际间隔在允许容差内'
  }
})
const headwayControlledTrainId = computed(() => primaryHeadwayProfile.value?.trainId ?? selectedDemoTrain.value?.id ?? '-')
const headwayFrontTrainId = computed(() => primaryHeadwayProfile.value?.frontTrainId ?? selectedFrontTrain.value?.id ?? '-')
const headwayRegulationAction = computed(() => primaryHeadwayProfile.value?.regulationAction ?? 'OBSERVE')
const lineEndMeters = computed(() => {
  const ends = snapshot.value?.trackSegments.map((segment) => segment.endMeters) ?? []
  return ends.length > 0 ? Math.max(...ends) : null
})
const currentGapMeters = computed(() => spatialGapMeters(selectedDemoTrain.value, selectedFrontTrain.value))
const dwellStartTicks = ref<Record<string, { stationId: string; tick: number }>>({})
const selectedTrainLimitSummary = computed(() => {
  const train = selectedDemoTrain.value
  return train ? effectiveLimitText(train) : '未选择列车'
})
const planTrainOptions = computed(() => {
  const liveTrainIds = new Set(trains.value.map((train) => train.id))
  return (plan.value?.services ?? []).filter((service) => !liveTrainIds.has(service.trainId))
})
const selectedTrainProfile = computed(() =>
  selectedDemoTrain.value
    ? dispatch.value.trainProfiles.find((profile) => profile.trainId === selectedDemoTrain.value?.id) ?? null
    : null
)
const selectedTrainService = computed(() =>
  selectedDemoTrain.value
    ? plan.value?.services.find((service) => service.trainId === selectedDemoTrain.value?.id) ?? null
    : null
)
const selectedTrainAuthority = computed(() =>
  selectedDemoTrain.value ? authorityByTrain.value.get(selectedDemoTrain.value.id) ?? null : null
)
const selectedTrainReservation = computed(() =>
  selectedDemoTrain.value ? routeReservationForTrain(selectedDemoTrain.value.id) : null
)
const selectedTrainRouteState = computed(() => {
  const reservation = selectedTrainReservation.value
  const train = selectedDemoTrain.value
  return routes.value.find((route) => route.routeId === reservation?.routeId)
    ?? routes.value.find((route) => route.establishedByTrainId === train?.id)
    ?? null
})
const selectedTrainCommands = computed(() => {
  const trainId = selectedDemoTrain.value?.id
  if (!trainId) return []
  return dispatch.value.activeCommands.filter((command) =>
    command.trainId === trainId || command.regulatedTrainId === trainId
  )
})
const selectedTrainOpenDisturbances = computed(() => {
  const trainId = selectedDemoTrain.value?.id
  if (!trainId) return []
  return dispatch.value.openDisturbances.filter((disturbance) =>
    disturbance.trainId === trainId || disturbance.regulatedTrainId === trainId
  )
})
const selectedTrainSuggestion = computed(() => {
  const profile = selectedTrainProfile.value
  if (!selectedDemoTrain.value) return { label: '未选择列车', action: 'NONE', reason: '请先在列车运行总览中选择调度对象。' }
  if (!profile) return { label: '继续观测', action: 'OBSERVE', reason: '当前没有稳定的实际发车间隔数据，调度不应提前下发追赶/放慢。' }
  if (profile.regulationAction === 'SLOW_DOWN') {
    return { label: '本车放慢', action: 'SLOW_DOWN', reason: `实际间隔 ${formatSeconds(profile.headwayActualSeconds)}，低于目标区间，需要拉开与前车距离。` }
  }
  if (profile.regulationAction === 'CATCH_UP') {
    return { label: '本车追赶', action: 'CATCH_UP', reason: `实际间隔 ${formatSeconds(profile.headwayActualSeconds)}，高于目标区间，需要压缩运行间隔。` }
  }
  if (profile.headwayState === 'TOO_SHORT') {
    return { label: '本车放慢', action: 'SLOW_DOWN', reason: '间隔状态为过短，建议延长停站或降低运行节奏。' }
  }
  if (profile.headwayState === 'TOO_LONG') {
    return { label: '本车追赶', action: 'CATCH_UP', reason: '间隔状态为过长，建议缩短停站或请求追赶运行。' }
  }
  return { label: '继续观测', action: 'OBSERVE', reason: '当前间隔未触发明确调节动作。' }
})
const canApplySuggestion = computed(() =>
  ['SLOW_DOWN', 'CATCH_UP'].includes(selectedTrainSuggestion.value.action)
    && ['TOO_SHORT', 'TOO_LONG'].includes(selectedTrainProfile.value?.headwayState ?? '')
)
const suggestionDisabledReason = computed(() => {
  if (!selectedDemoTrain.value) return '请先选择调度对象'
  if (!selectedTrainProfile.value) return '没有实际发车间隔数据，暂不下发间隔调节'
  if (!canApplySuggestion.value) return '当前建议为继续观察，不需要下发调度命令'
  return ''
})
const trainOverviewRows = computed(() =>
  orderedTrains.value.map((train) => {
    const profile = dispatch.value.trainProfiles.find((item) => item.trainId === train.id) ?? null
    const authority = authorityByTrain.value.get(train.id) ?? null
    const reservation = routeReservationForTrain(train.id)
    const routeState = routes.value.find((route) => route.routeId === reservation?.routeId)
      ?? routes.value.find((route) => route.establishedByTrainId === train.id)
      ?? null
    const command = dispatch.value.activeCommands.find((item) =>
      item.trainId === train.id || item.regulatedTrainId === train.id
    )
    return {
      train,
      profile,
      authority,
      reservation,
      routeState,
      command,
      dispatchRouteId: dispatchRouteText(train, reservation, routeState),
      dispatchRouteState: dispatchRouteStateText(reservation, routeState),
      attention: trainAttentionState(train, profile, reservation, command)
    }
  })
)
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
      delta: '只调节当前本车'
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
      label: '计划目标间隔',
      before: formatSeconds(baseline.targetHeadwaySeconds),
      after: formatSeconds(baseline.commandTargetHeadwaySeconds ?? dispatch.value.targetHeadwaySeconds),
      delta: formatDelta(baseline.commandTargetHeadwaySeconds ?? dispatch.value.targetHeadwaySeconds, baseline.targetHeadwaySeconds, 's')
    },
    {
      label: '注入实际间隔',
      before: formatSeconds(baseline.scenarioActualHeadwaySeconds),
      after: formatSeconds(targetProfile?.headwayActualSeconds ?? null),
      delta: baseline.scenarioActualHeadwaySeconds === null ? '-' : '扰动场景输入'
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
  scenarioActualHeadwaySeconds: number | null
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
    SHORTEN_DWELL: '本车追赶：缩短停站',
    EXTEND_DWELL: '本车放慢：延长停站',
    HEADWAY_ADJUST: '本车间隔调节',
    SPEED_BIAS: '本车速度偏置',
    SPEED_LIMIT: '临时限速',
    TEMP_SPEED_LIMIT: '临时限速',
    SPEED_FACTOR: '速度系数',
    LIMIT_FACTOR: '限速系数',
    HOLD: '扣车',
    HOLD_TRAIN: '扣车',
    DEPART: '发车',
    REQUEST_ROUTE: '申请进路',
    REROUTE: '重排进路',
    HEADWAY_TOO_SHORT: '本车放慢',
    HEADWAY_TOO_LONG: '本车追赶',
    SCHEDULE_LATE: '本车晚点恢复',
  }
  return labels[type] ?? type
}

const commandStatusLabel = (statusText: string) => {
  const labels: Record<string, string> = {
    PENDING: '待下发',
    SENT: '已下发',
    APPLIED: '约束已出现',
    EFFECT_CONFIRMED: '效果已闭环',
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
    SENT: '指令已下发到后端，尚未看到可核对的 MA、速度、停站或进路状态变化。',
    APPLIED: '已看到中间约束或车辆状态变化，但还不等于最终运行效果恢复。',
    EFFECT_CONFIRMED: '调度确认目标效果已满足，指令可以退出持续约束。',
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

const lineRegulationStatusLabel = (statusText: string) => {
  const labels: Record<string, string> = {
    NO_DATA: '等待数据',
    NO_ACTION_NEEDED: '无需调节',
    OBSERVING: '观察中',
    COMMANDS_PROPOSED: '已生成方案',
  }
  return labels[statusText] ?? statusText
}

const lineDecisionStatusLabel = (statusText: string) => {
  const labels: Record<string, string> = {
    COMMAND_PROPOSED: '已选中',
    OBSERVE: '观察',
    OBSERVE_ACTIVE_COMMAND: '已有命令',
    OBSERVE_CONFLICT_SUPPRESSED: '冲突抑制',
    OBSERVE_SIGNAL_CONSTRAINED: '信号约束',
  }
  return labels[statusText] ?? statusText
}

const signalConstraintLabel = (constraint: string) => {
  const labels: Record<string, string> = {
    NONE: '无',
    MA_LIMITED: 'MA受限',
    SPEED_LIMITED: '限速受限',
    ROUTE_BLOCKED: '进路受阻',
  }
  return labels[constraint] ?? constraint
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
    NONE: '本车正常运行',
    OBSERVE: '继续观测',
    NORMAL: '本车正常运行',
    SLOW_DOWN: '本车放慢',
    CATCH_UP: '本车追赶',
    SLOW_REAR_TRAIN: '本车放慢（兼容旧状态）',
    CATCH_UP_REAR_TRAIN: '本车追赶（兼容旧状态）',
  }
  return labels[action] ?? action
}

const headwayDirectionLabel = (direction: string | null | undefined) => {
  const labels: Record<string, string> = {
    TOO_SHORT: '间隔过小',
    TOO_LONG: '间隔过大',
    SCHEDULE_LATE: '本车晚点',
  }
  return direction ? labels[direction] ?? direction : '-'
}

const disturbanceScenarioLabel = (kind: 'tooShort' | 'tooLong' | 'late') => {
  const labels = {
    tooShort: '间隔过小扰动',
    tooLong: '间隔过大扰动',
    late: '本车晚点扰动',
  }
  return labels[kind]
}

const formatNumber = (value: number | null | undefined, digits = 1) => {
  if (value === null || value === undefined || Number.isNaN(value)) return '-'
  return value.toFixed(digits)
}

const disturbanceMetricText = (disturbance: DispatchDisturbance) => {
  if (!['TRAIN_REGULATION', 'HEADWAY_VIOLATION'].includes(disturbance.disturbanceType)) {
    return `偏差 ${formatNumber(disturbance.deviationValue, 0)}s`
  }
  if (disturbance.headwayDirection === 'SCHEDULE_LATE') {
    return `无前车参考，按运行图恢复本车晚点 · ${headwayActionLabel(disturbance.regulationAction ?? 'CATCH_UP')}`
  }
  const direction = disturbance.headwayDirection === 'TOO_SHORT' ? '过短' : '过长'
  return `${direction} · 实际 ${formatNumber(disturbance.actualHeadwaySec, 0)}s / 目标 ${formatNumber(disturbance.targetHeadwaySec, 0)}s · 本车动作 ${headwayActionLabel(disturbance.regulationAction ?? 'OBSERVE')}`
}

const formatPercent = (value: number | null | undefined) => {
  if (value === null || value === undefined || Number.isNaN(value)) return '-'
  return `${Math.round(value * 100)}%`
}

function clamp(value: number, minimum: number, maximum: number) {
  if (!Number.isFinite(value)) return minimum
  return Math.min(Math.max(value, minimum), maximum)
}

function dispatchLineNodeStyle(node: DispatchLineNode): Record<string, string> {
  return {
    left: `${node.x}%`,
    top: `${node.y}%`
  }
}

function dispatchLineEdgeLine(edge: DispatchLineEdge) {
  const source = dispatchLineNodeById.value.get(edge.source)
  const target = dispatchLineNodeById.value.get(edge.target)
  return {
    x1: source?.x ?? 0,
    y1: source?.y ?? 0,
    x2: target?.x ?? 0,
    y2: target?.y ?? 0
  }
}

function dispatchLineEdgePoint(edge: DispatchLineEdge, ratio: number) {
  const line = dispatchLineEdgeLine(edge)
  const normalized = clamp(ratio, 0, 1)
  return {
    x: line.x1 + (line.x2 - line.x1) * normalized,
    y: line.y1 + (line.y2 - line.y1) * normalized
  }
}

function dispatchLineEdgeLabelStyle(edge: DispatchLineEdge): Record<string, string> {
  const line = dispatchLineEdgeLine(edge)
  return {
    left: `${(line.x1 + line.x2) / 2}%`,
    top: `${(line.y1 + line.y2) / 2}%`
  }
}

function dispatchLineTrainStyle(marker: DispatchLineTrainMarker): Record<string, string> {
  return {
    left: `${marker.x}%`,
    top: `${marker.y}%`
  }
}

function dispatchLineNodeClass(node: DispatchLineNode) {
  const nodeRouteIds = dispatchLineNodeRouteIds(node)
  const active = nodeRouteIds.some((routeId) => activeDispatchRouteIds.value.has(routeId))
  const selected = selectedRouteId.value
    ? nodeRouteIds.includes(selectedRouteId.value)
    : selectedTrainReservation.value
      ? nodeRouteIds.includes(selectedTrainReservation.value.routeId)
      : false
  const plannerSelected = routePlannerSelectedNodeSet.value.has(node.id)
  const plannerPreview = routePlannerPreviewNodeSet.value.has(node.id)
  return [
    'dispatch-line-node',
    `node-${node.type}`,
    {
      active,
      selected,
      'planner-selectable': routePlannerOpen.value && isRoutePlannerNode(node),
      'planner-selected': routePlannerOpen.value && plannerSelected,
      'planner-preview': plannerPreview
    }
  ]
}

function dispatchLineNodeRouteIds(node: DispatchLineNode) {
  const ids = new Set<string>()
  const pointIds = new Set([node.id])
  if (node.stationId) pointIds.add(node.stationId)
  for (const template of operationRouteTemplates.value) {
    if (template.pointIds.some((pointId) => pointIds.has(pointId))) {
      ids.add(template.routeId)
    }
  }
  for (const edge of dispatchLineEdges) {
    if (edge.source !== node.id && edge.target !== node.id) continue
    for (const routeId of dispatchLineRouteIdsBySegmentId.value.get(edge.segmentId) ?? []) {
      ids.add(routeId)
    }
  }
  return [...ids]
}

function dispatchLineEdgeClass(edge: DispatchLineVisibleEdge) {
  const selected = selectedRouteId.value
    ? edge.routeIds.includes(selectedRouteId.value)
    : selectedTrainReservation.value
      ? edge.routeIds.includes(selectedTrainReservation.value.routeId)
      : false
  const planned = routePlannerPreviewSegmentIds.value.has(edge.segmentId)
  return [
    'dispatch-line-edge',
    edge.state.toLowerCase(),
    { selected, planned }
  ]
}

function dispatchLineEdgeState(edge: DispatchLineEdge & { routeIds: string[] }): DispatchLineEdgeState {
  const reservation = routeReservations.value.find((item) => edge.routeIds.includes(item.routeId))
  if (reservation?.state === 'REJECTED' || reservation?.state === 'TIMEOUT') return 'REJECTED'
  if (reservation?.state === 'REQUESTED') return 'REQUESTED'
  if (reservation?.state === 'ACCEPTED') return 'ACCEPTED'

  const routeState = edge.routeIds
    .map((routeId) => dispatchLineRouteStateById.value.get(routeId)?.status)
    .find(Boolean)
  if (routeState === 'CONFLICTED' || routeState === 'REJECTED' || routeState === 'FAILED') return 'REJECTED'
  if (routeState === 'VALIDATING' || routeState === 'SETTING_SWITCHES') return 'REQUESTED'
  if (routeState === 'LOCKED' || routeState === 'OCCUPIED') return 'ACCEPTED'

  const track = dispatchLineTrackBySegmentId.value.get(edge.segmentId)
  if (track?.occupancy === 'FAULT' || track?.occupancy === 'BLOCKED') return 'FAULT'
  if (track?.occupancy === 'OCCUPIED' || track?.occupancy === 'RESERVED') return 'OCCUPIED'
  return 'FREE'
}

function dispatchLineEdgeTitle(edge: DispatchLineVisibleEdge) {
  const routeText = edge.routeIds.join(' / ')
  const reservationText = edge.reservationState ? routeReservationStateLabel(edge.reservationState) : '无预约'
  const routeStateText = edge.routeStatus ? routeStatusLabel(edge.routeStatus) : '未锁闭'
  return `${edge.segmentId} · ${routeText} · ${reservationText} · ${routeStateText}`
}

function isRoutePlannerNode(node: DispatchLineNode) {
  return Boolean(node.id)
}

function routeTemplateCandidate(
  template: OperationRouteTemplate,
  direction: 'UP' | 'DOWN'
): RoutePlannerCandidate | null {
  const directedTemplate = isDirectedSignalRoute(template.routeId)
  const pointIds = direction === 'UP' || directedTemplate ? template.pointIds : [...template.pointIds].reverse()
  const nodeIds = pointIds
    .map(pointIdToNodeId)
    .filter((nodeId): nodeId is string => Boolean(nodeId))
  if (pointIds.length < 2 || nodeIds.length < 2) return null
  const segmentIds = direction === 'UP' || directedTemplate ? template.segmentIds : [...template.segmentIds].reverse()

  return {
    key: `${template.routeId}:${direction}`,
    routeId: template.routeId,
    routeName: template.name,
    direction,
    pointIds,
    nodeIds,
    stationIds: nodeIds
      .map((nodeId) => dispatchLineNodeById.value.get(nodeId)?.stationId)
      .filter((stationId): stationId is string => Boolean(stationId)),
    segmentIds
  }
}

function routeTemplateDirections(routeId: string): Array<'UP' | 'DOWN'> {
  if (routeId === 'R_UP' || routeId === 'R_TB_LIB' || routeId === 'R_TB_GGZ') return ['UP']
  if (routeId === 'R_DOWN') return ['DOWN']
  return ['UP', 'DOWN']
}

function isDirectedSignalRoute(routeId: string) {
  return routeId === 'R_UP'
    || routeId === 'R_DOWN'
    || routeId === 'R_TB_LIB'
    || routeId === 'R_TB_GGZ'
}

function handleDispatchLineNodeClick(node: DispatchLineNode) {
  if (!routePlannerOpen.value) return
  const existingIndex = routePlannerNodeIds.value.indexOf(node.id)
  if (existingIndex >= 0) {
    routePlannerNodeIds.value = routePlannerNodeIds.value.slice(0, existingIndex + 1)
  } else {
    routePlannerNodeIds.value = [...routePlannerNodeIds.value, node.id]
  }
  selectedOperationPlanId.value = ''
  routePlannerMessage.value = ''
}

function clearRoutePlanner() {
  routePlannerNodeIds.value = []
  routePlannerCandidateKey.value = ''
  routePlannerMessage.value = ''
}

function removeRoutePlannerStop() {
  routePlannerNodeIds.value = routePlannerNodeIds.value.slice(0, -1)
  routePlannerMessage.value = ''
}

async function createOperationPlan() {
  const candidate = selectedRoutePlannerCandidate.value
  const train = routePlannerAutoTrain.value
  if (!candidate || !train) {
    routePlannerMessage.value = operationRouteTemplates.value.length === 0
      ? '尚未加载到信号端既有运营路线模板，不能由调度端自建路线。'
      : candidate
        ? '暂无可自动绑定的列车。'
        : '当前点选节点未匹配到信号端既有进路。'
    return
  }

  routePlannerMessage.value = '正在提交运营计划。'
  try {
    const plan = await dispatchApi.createOperationPlan({
      pointIds: candidate.pointIds,
      routeId: candidate.routeId,
      candidateKey: candidate.key,
      trainId: train.id,
      leadSeconds: normalizedPlannerSeconds(routePlannerLeadSeconds.value, 0, 3600),
      headwaySeconds: normalizedPlannerSeconds(routePlannerHeadwaySeconds.value, 30, 3600)
    })
    selectedOperationPlanId.value = plan.planId
    selectedRouteId.value = plan.routeId
    selectTrain(plan.trainId)
    await refreshSimulationSnapshot()
    routePlannerMessage.value = `已生成 ${plan.planId}：${plan.trainId} / ${plan.routeId} / ${formatPlannedTime(plan.plannedDepartureAt)}`
  } catch (error) {
    routePlannerMessage.value = error instanceof Error ? `运营计划生成失败：${error.message}` : '运营计划生成失败'
  }
}

function selectOperationPlan(planId: string) {
  const plan = generatedOperationPlans.value.find((item) => item.planId === planId)
  if (!plan) return
  selectedOperationPlanId.value = planId
  selectedRouteId.value = plan.routeId
  selectTrain(plan.trainId)
}

async function removeOperationPlan(planId: string) {
  try {
    const plan = await dispatchApi.cancelOperationPlan(planId)
    if (selectedOperationPlanId.value === planId) selectedOperationPlanId.value = ''
    await refreshSimulationSnapshot()
    routePlannerMessage.value = `${plan.planId} 已取消`
  } catch (error) {
    routePlannerMessage.value = error instanceof Error ? `取消计划失败：${error.message}` : '取消计划失败'
  }
}

function trainAssignmentScore(train: TrainState, origin: DispatchLineNode) {
  const stationBonus = origin.stationId && train.currentStationId === origin.stationId ? -100000 : 0
  const speedPenalty = train.speedMetersPerSecond > 0.5 ? 20000 : 0
  const faultPenalty = Math.max(0, train.faultLevel) * 100000
  const statusPenalty = ['DWELLING', 'STOPPED', 'READY'].includes(train.status) ? 0 : 8000
  return stationBonus
    + speedPenalty
    + faultPenalty
    + statusPenalty
    + Math.abs(train.positionMeters - dispatchLineNodeMileage(origin))
}

function dispatchLineNodeMileage(node: DispatchLineNode) {
  const values: number[] = []
  for (const edge of dispatchLineEdges) {
    const segment = dispatchLineTrackBySegmentId.value.get(edge.segmentId)
    if (!segment) continue
    if (edge.source === node.id) values.push(segment.startMeters)
    if (edge.target === node.id) values.push(segment.endMeters)
  }
  if (values.length > 0) return values.reduce((sum, value) => sum + value, 0) / values.length
  return node.x * 100
}

function routePlannerCandidateLabel(candidate: RoutePlannerCandidate) {
  return `${candidate.routeId} ${candidate.routeName} / ${directionLabel(candidate.direction)} / ${candidate.segmentIds.join(' -> ')}`
}

function directionLabel(direction: 'UP' | 'DOWN') {
  return direction === 'UP' ? '正向' : '反向'
}

function operationPlanTitle(plan: OperationPlanView) {
  return `${plan.planId} · ${plan.trainId} · ${plan.routeId} · ${plan.status}`
}

function operationPlanPathText(plan: OperationPlanView) {
  const via = plan.viaPointIds.length > 0 ? ` / 经由 ${plan.viaPointIds.map(pointLabel).join('、')}` : ''
  return `${pointLabel(plan.originPointId)} -> ${pointLabel(plan.destinationPointId)}${via} / ${formatPlannedTime(plan.plannedDepartureAt)}`
}

function normalizedPlannerSeconds(value: number, minimum: number, maximum: number) {
  if (!Number.isFinite(value)) return minimum
  return Math.min(Math.max(Math.round(value), minimum), maximum)
}

function routePlannerPointId(node: DispatchLineNode) {
  return node.stationId ?? node.id
}

function pointIdToNodeId(pointId: string) {
  return dispatchLineNodeByPointId.value.get(pointId)?.id ?? null
}

function pointLabel(pointId: string) {
  return dispatchLineNodeByPointId.value.get(pointId)?.label ?? pointId
}

function formatPlannedTime(value: string | null | undefined) {
  if (!value) return '-'
  const parsed = new Date(value)
  if (Number.isNaN(parsed.getTime())) return value
  return parsed.toLocaleTimeString('zh-CN', { hour12: false })
}

async function loadDispatchRoutes() {
  try {
    const [routeList, templates] = await Promise.all([
      dispatchApi.routeList(),
      dispatchApi.operationRouteTemplates()
    ])
    dispatchRouteList.value = routeList
    operationRouteTemplates.value = templates
    selectedRouteId.value ||= dispatchRouteList.value[0]?.routeId ?? ''
  } catch (error) {
    routeOperationMessage.value = error instanceof Error ? `信号进路数据加载失败：${error.message}` : '信号进路数据加载失败'
  }
}

async function requestSelectedRoute() {
  const routeId = selectedRouteId.value
  const trainId = selectedRouteTrainId.value || selectedDemoTrain.value?.id || ''
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

    await refreshSimulationSnapshot()
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

watch(
  routePlannerCandidates,
  (candidates) => {
    if (candidates.length === 0) {
      routePlannerCandidateKey.value = ''
      return
    }
    if (!candidates.some((candidate) => candidate.key === routePlannerCandidateKey.value)) {
      routePlannerCandidateKey.value = candidates[0]?.key ?? ''
    }
  },
  { immediate: true }
)

watch(
  trains,
  (currentTrains) => {
    if (selectedTrainId.value && !currentTrains.some((train) => train.id === selectedTrainId.value)) {
      selectedTrainId.value = ''
      selectedRouteTrainId.value = ''
    }
  },
  { immediate: true }
)

watch(
  [trains, tick],
  () => {
    const next: Record<string, { stationId: string; tick: number }> = {}
    for (const train of trains.value) {
      if (!isDwellingForDisplay(train)) continue
      const stationId = observedStationKey(train)
      const previous = dwellStartTicks.value[train.id]
      next[train.id] = previous && previous.stationId === stationId
        ? previous
        : { stationId, tick: tick.value }
    }
    dwellStartTicks.value = next
  },
  { immediate: true }
)

async function submitLoopDemoCommand() {
  const targetTrain = selectedDemoTrain.value
  if (!targetTrain) throw new Error('请先在上方列车运行表选择调度对象')
  captureBaseline('SPEED_LIMIT', targetTrain)
  await dispatchApi.submitCommand({
    trainId: targetTrain.id,
    commandType: 'SPEED_LIMIT',
    detail: String(normalizedSpeedLimit())
  })
  await refreshSimulationSnapshot()
}

async function cancelCommand(commandId: string) {
  await dispatchApi.cancelCommand(commandId)
  await refreshSimulationSnapshot()
}

function selectTrain(trainId: string) {
  selectedTrainId.value = trainId
  selectedRouteTrainId.value = trainId
  manualActionMessage.value = ''
}

async function acceptSuggestedAction() {
  const action = selectedTrainSuggestion.value.action
  if (action === 'SLOW_DOWN') {
    await runManualAction('采纳本车放慢建议', () => submitHeadwayScenario('tooShort'))
    return
  }
  if (action === 'CATCH_UP') {
    await runManualAction('采纳本车追赶建议', () => submitHeadwayScenario('tooLong'))
    return
  }
  manualActionMessage.value = suggestionDisabledReason.value || '当前没有可下发的调度建议'
}

async function requestRouteForSelected() {
  if (selectedDemoTrain.value) {
    selectedRouteTrainId.value = selectedDemoTrain.value.id
  }
  await requestSelectedRoute()
}

async function submitHeadwayScenario(kind: 'tooShort' | 'tooLong' | 'late') {
  const targetTrain = selectedDemoTrain.value
  if (!targetTrain) throw new Error('请先在上方列车运行表选择调度对象')
  const targetHeadwaySec = dispatch.value.targetHeadwaySeconds
  const actualHeadwaySec = kind === 'tooShort'
    ? normalizedHeadway(demoShortHeadwaySec.value)
    : kind === 'tooLong'
      ? normalizedHeadway(demoLongHeadwaySec.value)
      : 0
  const headwayDirection = kind === 'tooShort'
    ? 'TOO_SHORT'
    : kind === 'tooLong'
      ? 'TOO_LONG'
      : 'SCHEDULE_LATE'
  const violationSec = kind === 'tooShort'
    ? Math.max(30, targetHeadwaySec * headwayShrinkRatio - actualHeadwaySec)
    : kind === 'tooLong'
      ? Math.max(30, actualHeadwaySec - targetHeadwaySec * headwayExpandRatio)
      : Math.max(30, selectedTrainProfile.value?.departureDelaySeconds ?? 90)

  captureBaseline(
    kind === 'tooShort' ? 'HEADWAY_TOO_SHORT' : kind === 'tooLong' ? 'HEADWAY_TOO_LONG' : 'SCHEDULE_LATE',
    targetTrain,
    null,
    actualHeadwaySec
  )
  const event = await dispatchApi.injectDemoDisturbance({
    trainId: targetTrain.id,
    targetHeadwaySec,
    actualHeadwaySec,
    violationSec,
    headwayDirection,
    type: 'TRAIN_REGULATION',
    stationId: targetTrain.currentStationId || undefined
  })
  await refreshSimulationSnapshot()
  manualActionMessage.value = `已注入${disturbanceScenarioLabel(kind)}：${event.id}，等待后端策略在下一轮生成本车调度指令。`
}

async function runManualAction(label: string, action: () => Promise<void>) {
  if (manualActionPending.value) return
  if (!selectedDemoTrain.value) {
    manualActionMessage.value = '请先在上方列车运行表选择调度对象。'
    return
  }

  manualActionPending.value = true
  manualActionMessage.value = `${label}已提交，等待后端总循环处理。`
  try {
    await action()
    if (!manualActionMessage.value.includes('已注入')) {
      manualActionMessage.value = `${label}已下发，请在运行表和闭环追踪中观察 MA、速度、停站或进路状态变化。`
    }
  } catch (error) {
    manualActionMessage.value = error instanceof Error ? `${label}失败：${error.message}` : `${label}失败。`
  } finally {
    manualActionPending.value = false
  }
}

async function submitManualHeadwayDemo(kind: 'tooShort' | 'tooLong') {
  await runManualAction(kind === 'tooShort' ? '演示间隔过小' : '演示间隔过大', () => submitHeadwayScenario(kind))
}

async function submitManualLateDemo() {
  await runManualAction('演示本车晚点', () => submitHeadwayScenario('late'))
}

async function submitManualSpeedLimit() {
  await runManualAction('人工下发限速', submitLoopDemoCommand)
}

async function submitManualRouteRequest() {
  if (!selectedRouteId.value) {
    manualActionMessage.value = '请先选择要人工申请的进路。'
    return
  }
  await runManualAction('人工申请进路', requestRouteForSelected)
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

function observedStationKey(train: TrainState) {
  const station = stationObservation(train.id, train.currentStationId)
  return station === '-' ? '' : station
}

function isDwellingForDisplay(train: TrainState) {
  const hasStation = observedStationKey(train).length > 0
  return train.status === 'DWELLING'
    || train.dynamicsState === 'STATION_STOPPED'
    || (hasStation && train.speedMetersPerSecond <= 0.5)
}

function observedDwellSeconds(train: TrainState) {
  if (!isDwellingForDisplay(train)) return '-'
  if ((train.dwellElapsedSeconds ?? 0) > 0) return `${train.dwellElapsedSeconds}s`
  const marker = dwellStartTicks.value[train.id]
  if (!marker) return '0s'
  return `${Math.max(0, tick.value - marker.tick)}s`
}

function commandSpeedLimit(command: { payload?: Record<string, unknown>; detail?: string | null }) {
  const value = command.payload?.detail ?? command.payload?.targetSpeedMetersPerSecond ?? command.detail
  if (typeof value === 'number' && Number.isFinite(value)) return value
  if (typeof value === 'string' && value.trim().length > 0) {
    const parsed = Number.parseFloat(value)
    return Number.isFinite(parsed) ? parsed : null
  }
  return null
}

function commandEvidence(command: { commandType: string; trainId: string; status: string; reason: string; detail?: string | null; payload?: Record<string, unknown> }) {
  const payload = command.payload ?? {}
  const details = typeof payload.lastFeedbackDetails === 'object' && payload.lastFeedbackDetails !== null
    ? payload.lastFeedbackDetails as Record<string, unknown>
    : {}
  const feedbackReason = textPayloadValue(payload.lastFeedbackReason)
  const pieces: string[] = []
  const direction = textPayloadValue(payload.headwayDirection)
  if (direction !== '-') pieces.push(headwayDirectionLabel(direction))
  const action = textPayloadValue(payload.regulationAction)
  if (action !== '-') pieces.push(headwayActionLabel(action))
  const ratio = numberPayloadValue(payload.speedBiasRatio)
  if (ratio !== null) pieces.push(`速度偏置 ${ratio.toFixed(2)}`)
  const baselineError = numberPayloadValue(payload.baselineHeadwayErrorSec)
  if (baselineError !== null) pieces.push(`基线偏差 ${formatSeconds(baselineError)}`)
  const targetHeadway = numberPayloadValue(payload.targetHeadwaySec)
  const actualHeadway = numberPayloadValue(payload.actualHeadwaySec)
  if (targetHeadway !== null || actualHeadway !== null) {
    pieces.push(`实际/目标 ${formatSeconds(actualHeadway)} / ${formatSeconds(targetHeadway)}`)
  }
  const deltaDwell = numberPayloadValue(payload.deltaDwellSec)
  if (deltaDwell !== null) pieces.push(`停站调整 ${deltaDwell > 0 ? '+' : ''}${deltaDwell.toFixed(0)}s`)
  if (details.actualSpeed !== undefined) pieces.push(`实速 ${formatNumber(numberPayloadValue(details.actualSpeed))} m/s`)
  if (details.zeroSpeed !== undefined) pieces.push(`零速 ${details.zeroSpeed ? '是' : '否'}`)
  if (details.constraintReason !== undefined) pieces.push(`约束 ${dynamicsReasonLabel(String(details.constraintReason))}`)
  if (feedbackReason !== '-') pieces.push(`反馈 ${feedbackReason}`)
  if (pieces.length > 0) return pieces.join(' / ')

  const targetTrain = trains.value.find((train) => train.id === command.trainId)
  const authority = authorityByTrain.value.get(command.trainId)
  if (['SPEED_LIMIT', 'TEMP_SPEED_LIMIT'].includes(command.commandType)) {
    const targetLimit = commandSpeedLimit(command)
    return [
      `目标 ${formatMps(targetLimit)}`,
      `MA ${formatMps(authority?.speedLimitMetersPerSecond ?? null)}`,
      `车辆 ${formatMps(targetTrain?.speedLimitMetersPerSecond ?? null)}`
    ].join(' / ')
  }
  if (authority) return `MA ${formatMps(authority.speedLimitMetersPerSecond)} / ${authority.reason}`
  return command.reason || '-'
}

function numberPayloadValue(value: unknown) {
  if (typeof value === 'number') return value
  if (typeof value === 'string') {
    const parsed = Number.parseFloat(value)
    return Number.isFinite(parsed) ? parsed : null
  }
  return null
}

function effectiveLimitText(train: TrainState) {
  const authority = authorityByTrain.value.get(train.id)
  const effective = Math.min(
    train.speedLimitMetersPerSecond,
    authority?.speedLimitMetersPerSecond ?? train.speedLimitMetersPerSecond
  )
  return `${formatMps(effective)}（车辆 ${formatMps(train.speedLimitMetersPerSecond)} / MA ${formatMps(authority?.speedLimitMetersPerSecond ?? null)}）`
}

function conciseLimitText(train: TrainState) {
  const authority = authorityByTrain.value.get(train.id)
  const effective = Math.min(
    train.speedLimitMetersPerSecond,
    authority?.speedLimitMetersPerSecond ?? train.speedLimitMetersPerSecond
  )
  return formatMps(effective)
}

function rowClass(train: TrainState) {
  return {
    selected: train.id === selectedDemoTrain.value?.id,
    dwelling: isDwellingForDisplay(train)
  }
}

function routeReservationForTrain(trainId: string) {
  const priority = ['REQUESTED', 'ACCEPTED', 'REJECTED', 'TIMEOUT', 'CANCELLED', 'RELEASED', 'EXPIRED']
  return [...routeReservations.value]
    .filter((reservation) => reservation.trainId === trainId)
    .sort((first, second) => {
      const firstRank = priority.indexOf(first.state)
      const secondRank = priority.indexOf(second.state)
      return (firstRank === -1 ? priority.length : firstRank) - (secondRank === -1 ? priority.length : secondRank)
    })[0] ?? null
}

function trainAttentionState(
  train: TrainState,
  profile: { headwayState: string; regulationAction: string } | null,
  reservation: { state: string; retryable: boolean; failureCode: string | null } | null,
  command: { status: string } | undefined
) {
  if (reservation?.state === 'REJECTED' || reservation?.state === 'TIMEOUT') return 'ROUTE_BLOCKED'
  if (command && ['PENDING', 'SENT', 'APPLIED'].includes(command.status)) return 'COMMAND_ACTIVE'
  if (profile?.headwayState === 'TOO_SHORT' || profile?.headwayState === 'TOO_LONG') return profile.headwayState
  if (train.speedMetersPerSecond <= 0.1 && train.status !== 'DWELLING' && !nearTerminal(train)) return 'STOPPED'
  return 'NORMAL'
}

function attentionLabel(attention: string) {
  const labels: Record<string, string> = {
    NORMAL: '正常',
    TOO_SHORT: '间隔过短',
    TOO_LONG: '间隔过长',
    COMMAND_ACTIVE: '干预中',
    ROUTE_BLOCKED: '进路异常',
    STOPPED: '停车确认',
  }
  return labels[attention] ?? attention
}

function nextStopText(train: TrainState) {
  const service = plan.value?.services.find((item) => item.trainId === train.id)
  if (!service) return '-'
  if (train.currentStationId) {
    const currentIndex = service.stops.findIndex((stop) => stop.stationId === train.currentStationId)
    if (currentIndex >= 0) return service.stops[currentIndex + 1]?.stationId ?? '终点'
  }
  const routeStation = service.stops.find((stop) => stop.arrivalOffsetSec > 0 && stop.stationId !== service.stops[0]?.stationId)
  return routeStation?.stationId ?? service.stops[1]?.stationId ?? '-'
}

function selectedCurrentStopPlanText() {
  const train = selectedDemoTrain.value
  const service = selectedTrainService.value
  if (!train || !service) return '-'
  const stationId = train.currentStationId ?? service.stops[0]?.stationId
  const stop = service.stops.find((item) => item.stationId === stationId)
  if (!stop) return stationId ?? '-'
  return `${stop.stationId} / 到 ${formatSeconds(stop.arrivalOffsetSec)} / 发 ${formatSeconds(stop.departureOffsetSec)}`
}

function selectedNextStopPlanText() {
  const train = selectedDemoTrain.value
  const service = selectedTrainService.value
  if (!train || !service) return '-'
  const nextStationId = nextStopText(train)
  const stop = service.stops.find((item) => item.stationId === nextStationId)
  if (!stop) return nextStationId
  return `${stop.stationId} / 到 ${formatSeconds(stop.arrivalOffsetSec)} / 发 ${formatSeconds(stop.departureOffsetSec)}`
}

function routeStateForDisplay(routeStatus: string | null | undefined, reservationState: string | null | undefined) {
  if (routeStatus) return routeStatusLabel(routeStatus)
  if (reservationState) return routeReservationStateLabel(reservationState)
  return '未申请'
}

function dispatchRouteText(
  train: TrainState,
  reservation: { routeId: string } | null,
  routeState: { routeId: string } | null
) {
  if (reservation?.routeId) return reservation.routeId
  if (routeState?.routeId) return routeState.routeId
  return train.routeId && train.routeId !== 'topology-demo' ? train.routeId : '未由调度申请'
}

function dispatchRouteStateText(
  reservation: { state: string } | null,
  routeState: { status: string } | null
) {
  if (routeState?.status) return routeStatusLabel(routeState.status)
  if (reservation?.state) return routeReservationStateLabel(reservation.state)
  return '未由调度申请'
}

function captureBaseline(
  commandType: string,
  targetTrain: TrainState,
  commandTargetHeadwaySeconds: number | null = null,
  scenarioActualHeadwaySeconds: number | null = null
) {
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
    scenarioActualHeadwaySeconds,
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
        <p>实时跟随后端总循环查看调度指令、列车、MA、信号、既有进路和道岔；调度页只申请信号端已有进路，不新增轨道拓扑。</p>
      </section>
      <section class="debug-actions" aria-label="调度页操作">
        <button type="button" class="ghost-button" @click="$emit('back')">返回大屏</button>
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

    <section class="debug-panel dispatch-line-panel">
      <div class="panel-title dispatch-line-title">
        <h2>既有路线编排</h2>
        <div class="dispatch-line-title-actions">
          <span>{{ operationRouteTemplates.length }} 条信号模板</span>
          <span>{{ dispatchLineMappedRoutes.length }} 条已映射进路</span>
          <span v-if="generatedOperationPlans.length > 0">{{ generatedOperationPlans.length }} 条待发车</span>
        </div>
      </div>
      <p class="route-planner-source">
        运营路线只来自信号轨道端既有 route/template；调度端在这里选择模板、绑定列车和发车时间，不新增线路拓扑。
      </p>
      <div class="dispatch-line-workspace route-planner-panel" aria-label="既有路线编排">
        <section class="route-planner-card">
          <div class="route-planner-heading">
            <h3>选择信号路线模板</h3>
            <span>{{ routePlannerCandidates.length }} 个模板方向</span>
          </div>
          <label>
            <span>既有进路模板</span>
            <select v-model="routePlannerCandidateKey" :disabled="routePlannerCandidates.length === 0">
              <option v-if="routePlannerCandidates.length === 0" value="">暂无模板</option>
              <option
                v-for="candidate in routePlannerCandidates"
                :key="candidate.key"
                :value="candidate.key"
              >
                {{ routePlannerCandidateLabel(candidate) }}
              </option>
            </select>
          </label>
          <div class="route-stop-grid">
            <article>
              <span>站点序列</span>
              <strong>{{ selectedRoutePlannerCandidate?.stationIds.join(' -> ') || '-' }}</strong>
            </article>
            <article>
              <span>轨道区段</span>
              <strong>{{ selectedRoutePlannerCandidate?.segmentIds.join(' -> ') || '-' }}</strong>
            </article>
          </div>
          <div class="route-planner-fields">
            <label>
              <span>首车等待(s)</span>
              <input v-model.number="routePlannerLeadSeconds" type="number" min="0" max="3600" step="30">
            </label>
            <label>
              <span>计划间隔(s)</span>
              <input v-model.number="routePlannerHeadwaySeconds" type="number" min="30" max="3600" step="30">
            </label>
          </div>
          <div class="route-plan-preview">
            <article>
              <span>自动车辆</span>
              <strong>{{ routePlannerAutoTrain?.id ?? '-' }}</strong>
            </article>
            <article>
              <span>发车 tick</span>
              <strong>{{ routePlannerNextDepartureTick }}</strong>
            </article>
            <article>
              <span>候选进路</span>
              <strong>{{ selectedRoutePlannerCandidate?.routeId ?? '-' }}</strong>
            </article>
          </div>
          <button
            type="button"
            class="demo-button"
            :disabled="!canCreateOperationPlan"
            @click="createOperationPlan"
          >
            生成运营计划
          </button>
          <p v-if="routePlannerMessage" class="route-planner-message">{{ routePlannerMessage }}</p>
        </section>
        <section class="route-planner-card">
          <div class="route-planner-heading">
            <h3>待发车计划</h3>
            <span>{{ generatedOperationPlans.length }} 条</span>
          </div>
          <div v-if="generatedOperationPlans.length === 0" class="empty">暂无计划。</div>
          <div v-else class="operation-plan-list">
            <article
              v-for="item in generatedOperationPlans"
              :key="item.planId"
              :class="{ selected: item.planId === selectedOperationPlanId }"
            >
              <button type="button" @click="selectOperationPlan(item.planId)">
                <strong>{{ operationPlanTitle(item) }}</strong>
                <span>{{ operationPlanPathText(item) }}</span>
                <small>{{ item.segmentIds.join(' -> ') }}</small>
              </button>
              <button type="button" class="release-button" @click="removeOperationPlan(item.planId)">移除</button>
            </article>
          </div>
        </section>
      </div>
      <div class="dispatch-line-route-chips">
        <span
          v-for="routeId in dispatchLineMappedRoutes"
          :key="routeId"
          :class="{ active: activeDispatchRouteIds.has(routeId) || selectedRouteId === routeId }"
        >
          {{ routeId }}
        </span>
        <span v-if="dispatchLineUnmappedRoutes.length > 0" class="unmapped">
          未映射 {{ dispatchLineUnmappedRoutes.join(' / ') }}
        </span>
      </div>
    </section>

    <section class="debug-panel train-overview-panel">
      <div class="panel-title">
        <h2>列车运行表</h2>
        <span>车辆与信号实时状态；点击行后在下方进入调度处理</span>
      </div>
      <div class="table-wrap">
        <table class="overview-table">
          <thead>
            <tr>
              <th>列车</th>
              <th>运行状态</th>
              <th>位置(m)</th>
              <th>当前站/区间</th>
              <th>下一站</th>
              <th>停站(s)</th>
              <th>速度(m/s)</th>
              <th>有效限速(m/s)</th>
              <th>终点剩余(m)</th>
              <th>MA距离(m)</th>
              <th>车辆约束</th>
              <th>牵引/制动</th>
              <th>满载率</th>
              <th>运行关注</th>
            </tr>
          </thead>
          <tbody>
            <tr v-if="trainOverviewRows.length === 0">
              <td colspan="14">暂无上线列车。等待运行计划发车或后端总循环推进。</td>
            </tr>
            <tr
              v-for="row in trainOverviewRows"
              :key="row.train.id"
              :class="rowClass(row.train)"
              :data-attention="row.attention"
              @click="selectTrain(row.train.id)"
            >
              <td><strong>{{ row.train.id }}</strong></td>
              <td>{{ trainStatusLabel(row.train.status) }}</td>
              <td>{{ formatNumber(row.train.positionMeters) }}</td>
              <td>{{ stationObservation(row.train.id, row.train.currentStationId) }}</td>
              <td>{{ nextStopText(row.train) }}</td>
              <td>{{ observedDwellSeconds(row.train) }}</td>
              <td>{{ formatNumber(row.train.speedMetersPerSecond) }}</td>
              <td>{{ conciseLimitText(row.train) }}</td>
              <td>{{ formatNumber(remainingToTerminal(row.train)) }}</td>
              <td>{{ formatNumber(row.authority ? row.authority.authorityEndMeters - row.train.positionMeters : row.train.movementAuthorityDistanceMeters) }}</td>
              <td>{{ dynamicsReasonLabel(row.train.dynamicsConstraintReason || '-') }}</td>
              <td>{{ row.train.tractionState }} / {{ row.train.brakeState }}</td>
              <td>{{ formatPercent(row.train.loadRate) }}</td>
              <td><span :data-attention="row.attention">{{ attentionLabel(row.attention) }}</span></td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>

    <section class="debug-panel selected-workbench">
      <div class="panel-title">
        <h2>调度处理表：{{ selectedDemoTrain?.id || '未选择对象' }}</h2>
        <span>{{ selectedTrainSuggestion.label }} / {{ selectedTrainSuggestion.reason }}</span>
      </div>
      <div v-if="!selectedDemoTrain" class="empty">请先在上方列车运行总览中选择一列车。</div>
      <div v-else class="selected-workbench-grid">
        <article>
          <span>运行计划</span>
          <strong>{{ selectedTrainService?.serviceId || selectedDemoTrain.serviceNo || '-' }}</strong>
          <small>当前 {{ selectedCurrentStopPlanText() }}</small>
          <small>下一站 {{ selectedNextStopPlanText() }}</small>
        </article>
        <article>
          <span>时间间隔</span>
          <strong>{{ selectedTrainProfile ? headwayStateLabel(selectedTrainProfile.headwayState) : '等待数据' }}</strong>
          <small>前车 {{ selectedTrainProfile?.frontTrainId || '-' }} / 实际 {{ formatSeconds(selectedTrainProfile?.headwayActualSeconds ?? null) }}</small>
          <small>建议 {{ selectedTrainSuggestion.label }}</small>
        </article>
        <article>
          <span>进路与信号</span>
          <strong>{{ selectedTrainReservation?.routeId || selectedTrainRouteState?.routeId || '待申请' }}</strong>
          <small>联锁 {{ routeStateForDisplay(selectedTrainRouteState?.status, selectedTrainReservation?.state) }}</small>
          <small>反馈 {{ selectedTrainReservation?.failureCode || selectedTrainReservation?.rejectReason || '-' }}</small>
        </article>
        <article>
          <span>车辆约束</span>
          <strong>{{ selectedTrainLimitSummary }}</strong>
          <small>MA {{ formatMeters(selectedTrainAuthority ? selectedTrainAuthority.authorityEndMeters - selectedDemoTrain.positionMeters : null) }}</small>
          <small>{{ dynamicsReasonLabel(selectedDemoTrain.dynamicsConstraintReason || '-') }}</small>
        </article>
      </div>

      <div v-if="selectedDemoTrain" class="object-action-panel">
        <div class="action-summary">
          <strong>系统建议：{{ selectedTrainSuggestion.label }}</strong>
          <span>{{ selectedTrainSuggestion.reason }}</span>
          <small v-if="suggestionDisabledReason">{{ suggestionDisabledReason }}</small>
        </div>
        <button
          type="button"
          class="demo-button"
          :disabled="!canApplySuggestion || manualActionPending"
          @click="acceptSuggestedAction"
        >
          {{ manualActionPending ? '提交中' : '采纳建议' }}
        </button>
        <button type="button" class="ghost-button" @click="manualToolsOpen = !manualToolsOpen">
          {{ manualToolsOpen ? '收起人工干预' : '人工干预/联调演示' }}
        </button>
      </div>

      <div v-if="selectedDemoTrain && manualToolsOpen" class="manual-tool-panel">
        <div class="manual-tool-note">
          <strong>人工干预/联调演示</strong>
          <span>这些按钮用于异常处置或联调验证。正常运营中，进路应由调度策略按计划自动申请；间隔调节应在出现明确间隔偏差后由系统建议触发。</span>
        </div>
        <label>
          <span>间隔过小实际值 s</span>
          <input v-model.number="demoShortHeadwaySec" type="number" min="30" max="900" step="30" />
        </label>
        <button type="button" :disabled="manualActionPending" @click="submitManualHeadwayDemo('tooShort')">注入：间隔过小</button>
        <label>
          <span>间隔过大实际值 s</span>
          <input v-model.number="demoLongHeadwaySec" type="number" min="30" max="900" step="30" />
        </label>
        <button type="button" :disabled="manualActionPending" @click="submitManualHeadwayDemo('tooLong')">注入：间隔过大</button>
        <button type="button" :disabled="manualActionPending" @click="submitManualLateDemo">注入：本车晚点</button>
        <label>
          <span>临时限速 m/s</span>
          <input v-model.number="demoSpeedLimitMps" type="number" min="1" max="22.2" step="0.5" />
        </label>
        <button type="button" :disabled="manualActionPending" @click="submitManualSpeedLimit">人工下发限速</button>
        <label>
          <span>人工申请进路</span>
          <select v-model="selectedRouteId" aria-label="选择进路">
            <option value="">选择进路</option>
            <option v-for="route in dispatchRouteList" :key="route.routeId" :value="route.routeId">
              {{ route.routeId }} · {{ route.name }} · {{ routeStatusLabel(route.status) }}
            </option>
          </select>
        </label>
        <button type="button" :disabled="manualActionPending || routeRequestPending" @click="submitManualRouteRequest">
          {{ manualActionPending || routeRequestPending ? '提交中' : '人工申请进路' }}
        </button>
        <p v-if="manualActionMessage" class="manual-action-message">{{ manualActionMessage }}</p>
        <p v-if="routeOperationMessage" class="route-operation-message">{{ routeOperationMessage }}</p>
      </div>

      <div v-if="selectedTrainCommands.length > 0 || selectedTrainOpenDisturbances.length > 0" class="selected-traces">
        <article v-for="command in selectedTrainCommands" :key="command.id">
          <strong>{{ commandLabel(command.commandType) }}</strong>
          <span :data-status="command.status">{{ commandStatusLabel(command.status) }}</span>
          <small>{{ commandEvidence(command) }}</small>
        </article>
        <article v-for="disturbance in selectedTrainOpenDisturbances" :key="disturbance.id">
          <strong>{{ disturbance.disturbanceType }}</strong>
          <span>{{ disturbance.status }}</span>
          <small>{{ disturbanceMetricText(disturbance) }}</small>
        </article>
      </div>
    </section>

    <section class="closure-overview-grid">
      <RunPlanPanel
        :plan="plan"
        :run-mode="dispatch.runMode"
        :target-headway-seconds="dispatch.targetHeadwaySeconds"
        :selected-train-id="selectedTrainId"
        @select-train="selectedTrainId = $event"
      />
      <StationHeadwayPanel :observations="dispatch.stationHeadways" />
      <RouteClosurePanel
        :decisions="dispatch.routeDecisions"
        :reservations="dispatch.routeReservations"
      />
    </section>

    <section class="headway-focus" :data-state="headwayViolation.state">
      <div class="panel-title">
        <h2>运行间隔关键指标</h2>
        <span>本车相对前车 / 带符号间隔偏差</span>
      </div>
      <div class="headway-kpi-grid">
        <article class="headway-kpi controlled">
          <span>调节本车</span>
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
          <span>本车调节动作</span>
          <strong>{{ headwayActionLabel(headwayRegulationAction) }}</strong>
          <small>所有自动调节只作用当前本车</small>
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

    <section class="line-regulation-panel" :data-status="lineRegulationPlan.status">
      <div class="panel-title">
        <h2>线路级调节方案</h2>
        <span>{{ lineRegulationStatusLabel(lineRegulationPlan.status) }} / {{ lineRegulationPlan.commandCount }} 条本车命令</span>
      </div>
      <div class="line-regulation-summary">
        <article>
          <span>目标间隔</span>
          <strong>{{ formatSeconds(lineRegulationPlan.targetHeadwaySec) }}</strong>
          <small>{{ lineRegulationPlan.objective }}</small>
        </article>
        <article>
          <span>当前最大偏差</span>
          <strong>{{ formatSeconds(lineRegulationPlan.currentMaxAbsHeadwayErrorSec) }}</strong>
          <small>全线绝对偏差</small>
        </article>
        <article>
          <span>预计最大偏差</span>
          <strong>{{ formatSeconds(lineRegulationPlan.predictedMaxAbsHeadwayErrorSec) }}</strong>
          <small>本轮动作后估计</small>
        </article>
        <article>
          <span>方案编号</span>
          <strong>{{ lineRegulationPlan.planId || '-' }}</strong>
          <small>{{ lineRegulationPlan.generatedAt ? formatPlannedTime(lineRegulationPlan.generatedAt) : '等待生成' }}</small>
        </article>
      </div>
      <p v-if="lineRegulationPlan.decisions.length === 0" class="empty">暂无线路级调节方案。等待列车形成有效间隔观测或注入扰动。</p>
      <div v-else class="line-decision-list">
        <article v-for="decision in lineRegulationPlan.decisions" :key="`${decision.trainId}-${decision.status}-${decision.commandId || decision.reason}`">
          <header>
            <strong>{{ decision.trainId }} · {{ headwayActionLabel(decision.action) }}</strong>
            <span :data-status="decision.status">{{ lineDecisionStatusLabel(decision.status) }}</span>
          </header>
          <dl>
            <div>
              <dt>参考前车</dt>
              <dd>{{ decision.frontTrainId || '-' }}</dd>
            </div>
            <div>
              <dt>实际/目标</dt>
              <dd>{{ formatSeconds(decision.currentHeadwaySec) }} / {{ formatSeconds(decision.targetHeadwaySec) }}</dd>
            </div>
            <div>
              <dt>偏差预测</dt>
              <dd>{{ formatSeconds(decision.currentHeadwayErrorSec) }} → {{ formatSeconds(decision.predictedHeadwayErrorSec) }}</dd>
            </div>
            <div>
              <dt>命令</dt>
              <dd>{{ commandLabel(decision.commandType) }}</dd>
            </div>
            <div>
              <dt>信号约束</dt>
              <dd>{{ signalConstraintLabel(decision.signalConstraint) }}</dd>
            </div>
          </dl>
          <p>{{ decision.reason }}</p>
        </article>
      </div>
    </section>

    <section class="debug-grid">
      <section class="debug-panel command-panel">
        <div class="panel-title">
          <h2>调度指令闭环追踪</h2>
          <span>当前对象的操作在上方工作台执行，这里记录全局指令链路</span>
        </div>
        <p v-if="dispatch.activeCommands.length === 0" class="empty">暂无调度指令。等待后端总循环推进，或发送上方演示指令后观察闭环状态。</p>
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
                <dt>调节本车</dt>
                <dd>{{ command.regulatedTrainId || command.trainId }}</dd>
              </div>
              <div>
                <dt>原因</dt>
                <dd>{{ command.reason }}</dd>
              </div>
            </dl>
            <p>{{ commandStatusHint(command.status) }}</p>
            <p class="command-evidence">观测依据：{{ commandEvidence(command) }}</p>
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
            <p>调节本车 {{ disturbance.regulatedTrainId || disturbance.trainId }} / 站点 {{ disturbance.stationId }}</p>
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
              / 本车动作 {{ headwayActionLabel(profile.regulationAction) }}
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
        暂无对比数据。点击“发送限速闭环”“本车放慢”或“本车追赶”后，这里会记录调度前状态并与当前状态实时比较。
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
          <h2>进路状态</h2>
          <span>调度申请、联锁接受、锁闭和释放追踪</span>
        </div>
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

.ghost-button.active {
  border-color: #1d4ed8;
  background: #eff6ff;
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
  grid-template-columns: minmax(180px, 1.2fr) minmax(130px, 0.8fr) auto minmax(150px, 1fr) auto minmax(150px, 1fr) auto;
  align-items: end;
  gap: 8px;
  margin-bottom: 12px;
}

.demo-action-card {
  display: grid;
  grid-template-rows: auto auto auto;
  gap: 10px;
  min-width: 0;
  border: 1px solid #dbeafe;
  border-radius: 12px;
  background: linear-gradient(180deg, #ffffff 0%, #f8fafc 100%);
  padding: 12px;
}

.demo-action-card.primary {
  border-color: #c4b5fd;
  background: linear-gradient(180deg, #faf5ff 0%, #ffffff 100%);
  box-shadow: inset 3px 0 0 #7c3aed;
}

.demo-action-card > div {
  display: grid;
  gap: 4px;
}

.demo-action-card strong {
  color: #172033;
  font-size: 14px;
}

.demo-action-card > div span,
.demo-action-card label span {
  color: #64748b;
  font-size: 12px;
  font-weight: 700;
}

.demo-control-bar input,
.demo-control-bar select {
  width: 100%;
  min-height: 36px;
  border: 1px solid #cbd5e1;
  border-radius: 8px;
  padding: 6px 10px;
  font: inherit;
}

.selected-train-note {
  margin: -4px 0 12px;
  border-left: 3px solid #2563eb;
  background: #eff6ff;
  color: #1e3a8a;
  padding: 8px 10px;
  font-size: 13px;
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

.dispatch-line-panel {
  margin-bottom: 12px;
}

.dispatch-line-route-chips {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 10px;
}

.dispatch-line-route-chips span {
  width: fit-content;
  border: 1px solid #dbeafe;
  border-radius: 999px;
  background: #f8fafc;
  color: #334155;
  padding: 4px 9px;
  font-size: 12px;
  font-weight: 800;
}

.dispatch-line-workspace {
  display: grid;
  gap: 12px;
}

.dispatch-line-route-chips {
  margin: 10px 0 0;
}

.dispatch-line-route-chips span.active {
  border-color: #2563eb;
  background: #eff6ff;
  color: #1d4ed8;
}

.dispatch-line-route-chips span.unmapped {
  border-color: #fecaca;
  background: #fef2f2;
  color: #b91c1c;
}

.route-planner-panel {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
  align-items: start;
  gap: 12px;
  min-width: 0;
}

.route-planner-card {
  display: grid;
  gap: 10px;
  border: 1px solid #dbeafe;
  border-radius: 8px;
  background: #f8fbff;
  padding: 12px;
}

.route-planner-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.route-planner-heading h3 {
  font-size: 15px;
}

.route-planner-heading span {
  width: fit-content;
  border-radius: 999px;
  background: #ede9fe;
  color: #6d28d9;
  padding: 3px 8px;
  font-size: 12px;
  font-weight: 800;
}

.route-planner-source {
  margin: 0;
  color: #475569;
  font-size: 12px;
  line-height: 1.5;
}

.route-stop-grid,
.route-plan-preview {
  display: grid;
  gap: 8px;
}

.route-stop-grid article,
.route-plan-preview article {
  display: grid;
  gap: 3px;
  min-width: 0;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  background: #fff;
  padding: 8px;
}

.route-stop-grid span,
.route-plan-preview span,
.route-planner-card label span {
  color: #64748b;
  font-size: 12px;
  font-weight: 800;
}

.route-stop-grid strong,
.route-plan-preview strong {
  overflow-wrap: anywhere;
  color: #172033;
  font-size: 13px;
  line-height: 1.35;
}

.route-planner-actions,
.route-planner-fields {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 8px;
}

.route-planner-card label {
  display: grid;
  gap: 4px;
  min-width: 0;
}

.route-planner-card select,
.route-planner-card input {
  width: 100%;
  min-height: 36px;
  border: 1px solid #cbd5e1;
  border-radius: 8px;
  background: #fff;
  padding: 6px 10px;
  font: inherit;
}

.route-planner-message {
  color: #1d4ed8;
  font-size: 13px;
  line-height: 1.5;
}

.operation-plan-list {
  display: grid;
  gap: 8px;
  max-height: 300px;
  overflow: auto;
}

.operation-plan-list article {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 6px;
  align-items: stretch;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  background: #fff;
  padding: 6px;
}

.operation-plan-list article.selected {
  border-color: #7c3aed;
  background: #f5f3ff;
}

.operation-plan-list article > button:first-child {
  display: grid;
  gap: 3px;
  justify-items: start;
  min-width: 0;
  border: 0;
  background: transparent;
  padding: 4px;
  text-align: left;
}

.operation-plan-list strong,
.operation-plan-list span,
.operation-plan-list small {
  overflow-wrap: anywhere;
  white-space: normal;
}

.operation-plan-list span,
.operation-plan-list small {
  color: #64748b;
  font-size: 12px;
}

.train-overview-panel,
.selected-workbench {
  margin-bottom: 12px;
}

.overview-table tbody tr {
  cursor: pointer;
}

.overview-table tbody tr:hover {
  background: #f8fafc;
}

.overview-table td strong {
  color: #172033;
}

.selected-workbench-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 10px;
}

.selected-workbench-grid article {
  display: grid;
  align-content: start;
  gap: 6px;
  min-height: 118px;
  min-width: 0;
  border: 1px solid #dbeafe;
  border-radius: 8px;
  background: #f8fbff;
  padding: 12px;
}

.selected-workbench-grid article span,
.object-action-panel label span {
  color: #475569;
  font-size: 12px;
  font-weight: 700;
}

.selected-workbench-grid article strong {
  overflow-wrap: anywhere;
  color: #172033;
  font-size: 18px;
  line-height: 1.3;
}

.object-action-panel {
  display: grid;
  grid-template-columns: minmax(280px, 1fr) auto auto;
  align-items: center;
  gap: 8px;
  margin-top: 12px;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  background: #f8fafc;
  padding: 10px;
}

.action-summary {
  display: grid;
  gap: 4px;
  min-width: 0;
}

.action-summary span {
  color: #475569;
  font-size: 12px;
  line-height: 1.4;
}

.action-summary small {
  color: #92400e;
}

.manual-tool-panel {
  display: grid;
  grid-template-columns: minmax(260px, 1.2fr) auto auto minmax(130px, 0.7fr) auto minmax(220px, 1fr) auto;
  align-items: end;
  gap: 8px;
  margin-top: 10px;
  border: 1px dashed #cbd5e1;
  border-radius: 8px;
  background: #fff;
  padding: 10px;
}

.manual-tool-note {
  display: grid;
  gap: 4px;
  min-width: 0;
}

.manual-tool-note span {
  color: #64748b;
  font-size: 12px;
  line-height: 1.45;
}

.object-action-panel label {
  display: grid;
  gap: 4px;
  min-width: 0;
}

.object-action-panel input,
.object-action-panel select,
.manual-tool-panel input,
.manual-tool-panel select {
  width: 100%;
  min-height: 36px;
  border: 1px solid #cbd5e1;
  border-radius: 8px;
  padding: 6px 10px;
  font: inherit;
}

.manual-tool-panel label {
  display: grid;
  gap: 4px;
  min-width: 0;
}

.manual-tool-panel label span {
  color: #475569;
  font-size: 12px;
  font-weight: 700;
}

.manual-tool-panel .route-operation-message,
.manual-tool-panel .manual-action-message {
  grid-column: 1 / -1;
}

.manual-action-message {
  margin: 0;
  border-left: 3px solid #2563eb;
  background: #eff6ff;
  color: #1e3a8a;
  padding: 8px 10px;
  font-size: 13px;
  line-height: 1.45;
}

.selected-traces {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 8px;
  margin-top: 12px;
}

.selected-traces article {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 4px 8px;
  align-items: center;
  border-left: 3px solid #2563eb;
  background: #eff6ff;
  padding: 8px 10px;
}

.selected-traces small {
  grid-column: 1 / -1;
}

.debug-banner,
.metric-grid,
.debug-grid,
.line-regulation-panel,
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
  grid-template-columns: 1.1fr repeat(5, minmax(0, 1fr));
  gap: 10px;
}

.closure-overview-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 14px;
  margin-bottom: 16px;
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

.line-regulation-panel {
  margin-bottom: 12px;
  border: 1px solid #c7d2fe;
  border-radius: 8px;
  background: #fbfdff;
  padding: 14px;
  box-shadow: 0 8px 24px rgba(15, 23, 42, 0.05);
}

.line-regulation-summary {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 10px;
  margin-bottom: 12px;
}

.line-regulation-summary article {
  display: grid;
  align-content: start;
  gap: 5px;
  min-width: 0;
  min-height: 86px;
  border: 1px solid #dbeafe;
  border-radius: 8px;
  background: #fff;
  padding: 12px;
}

.line-regulation-summary span,
.line-decision-list dt {
  color: #475569;
  font-size: 12px;
  font-weight: 700;
}

.line-regulation-summary strong {
  overflow-wrap: anywhere;
  color: #172033;
  font-size: 20px;
  line-height: 1.25;
}

.line-decision-list {
  display: grid;
  gap: 10px;
}

.line-decision-list article {
  display: grid;
  gap: 10px;
  border-left: 3px solid #4f46e5;
  background: #eef2ff;
  padding: 10px 12px;
}

.line-decision-list header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}

.line-decision-list header strong {
  overflow-wrap: anywhere;
}

.line-decision-list header span {
  flex: 0 0 auto;
  border-radius: 999px;
  background: #fff;
  color: #3730a3;
  padding: 4px 8px;
  font-size: 12px;
  font-weight: 800;
}

.line-decision-list dl {
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
  gap: 8px;
}

.line-decision-list dl div {
  min-width: 0;
}

.line-decision-list dd {
  overflow-wrap: anywhere;
  color: #172033;
  font-size: 13px;
  font-weight: 700;
}

.line-decision-list p {
  margin: 0;
  color: #475569;
  font-size: 13px;
  line-height: 1.45;
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

.dispatch-line-title {
  align-items: flex-start;
}

.dispatch-line-title h2 {
  flex: 0 0 auto;
}

.dispatch-line-title-actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: flex-end;
  gap: 8px;
  min-width: 0;
}

.dispatch-line-title-actions span {
  width: fit-content;
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

.command-card .command-evidence {
  margin-top: 6px;
  border-radius: 8px;
  background: #eef2ff;
  color: #3730a3;
  padding: 7px 8px;
  font-size: 12px;
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

.overview-table span[data-attention] {
  width: fit-content;
  border-radius: 999px;
  padding: 3px 8px;
  background: #dcfce7;
  color: #166534;
  font-size: 12px;
  font-weight: 700;
}

.overview-table span[data-attention='TOO_SHORT'],
.overview-table span[data-attention='TOO_LONG'],
.overview-table span[data-attention='STOPPED'] {
  background: #fef3c7;
  color: #92400e;
}

.overview-table span[data-attention='COMMAND_ACTIVE'] {
  background: #dbeafe;
  color: #1d4ed8;
}

.overview-table span[data-attention='ROUTE_BLOCKED'] {
  background: #fee2e2;
  color: #b91c1c;
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

.overview-table {
  min-width: 1420px;
}

th,
td {
  border-bottom: 1px solid #eef2f7;
  padding: 8px 6px;
  text-align: left;
  white-space: nowrap;
}

tbody tr.selected {
  background: #eff6ff;
}

tbody tr.dwelling td {
  color: #0f766e;
}

tbody tr.selected {
  background: #eff6ff;
}

tbody tr.dwelling td {
  color: #0f766e;
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
  .closure-overview-grid,
  .debug-grid,
  .debug-grid.three,
  .comparison-grid,
  .selected-workbench-grid,
  .selected-traces {
    grid-template-columns: 1fr;
  }

  .command-card dl,
  .switch-signal-grid,
  .demo-control-bar,
  .route-control-bar,
  .object-action-panel,
  .manual-tool-panel,
  .dispatch-line-workspace,
  .route-planner-panel {
    grid-template-columns: 1fr;
  }

  .dispatch-line-title {
    display: grid;
  }

  .dispatch-line-title-actions {
    justify-content: flex-start;
  }
}
</style>
