<script setup lang="ts">
import { computed } from 'vue'
import type {
  MovementAuthority,
  PowerSectionState,
  SignalState,
  SwitchState,
  TrackSegmentState,
  TrainState
} from '../../types/simulation'
import type { TopologyStation } from '../../types/topology'
import { occupancyLabel, signalAspectLabel, switchPositionLabel } from '../../shared/labels'

export interface DiagramLayers {
  track: boolean
  trains: boolean
  signals: boolean
  switches: boolean
  ma: boolean
  power: boolean
}

const props = withDefaults(
  defineProps<{
    segments: TrackSegmentState[]
    stations: TopologyStation[]
    trains?: TrainState[]
    authorities?: MovementAuthority[]
    signals?: SignalState[]
    switches?: SwitchState[]
    powerSections?: PowerSectionState[]
    layers?: DiagramLayers
    /** 进路高亮：这些区段加亮，其余压暗 */
    highlightSegmentIds?: string[] | null
    selectedTrainId?: string | null
    selectedSegmentId?: string | null
    /** 组件高度（px） */
    height?: number
    /** 投影模式：even=站点等距（默认，示意图）；proportional=真实里程比例 */
    mode?: 'even' | 'proportional'
    /** 区段 id → 视景边号（UDP segNo），用于 tooltip */
    rawSegmentIds?: Record<string, number> | null
  }>(),
  {
    trains: () => [],
    authorities: () => [],
    signals: () => [],
    switches: () => [],
    powerSections: () => [],
    layers: () => ({ track: true, trains: true, signals: true, switches: true, ma: true, power: true }),
    highlightSegmentIds: null,
    selectedTrainId: null,
    selectedSegmentId: null,
    height: 300,
    mode: 'even',
    rawSegmentIds: null
  }
)

const emit = defineEmits<{
  (e: 'select-train', trainId: string): void
  (e: 'select-segment', segmentId: string): void
}>()

/* ---------------- 几何计算 ---------------- */

const VIEW_W = 1200
const PAD_X = 46
const LANE_GAP = 52
/** 最上方车道之上给信号机/道岔/列车标签留的余量 */
const TOP_PAD = 50
/** 渡线股道标签：不占车道，画为跨车道斜线 */
const CROSSOVER_TRACK = 'crossover'

const lineLength = computed(() => {
  const ends = props.segments.map((segment) => segment.endMeters)
  return Math.max(1, ...ends)
})

/**
 * 里程 → x 投影。
 * - proportional：真实里程线性比例（工程视角）
 * - even（默认）：车站锚点均匀分布、锚点间分段线性（示意图视角，
 *   16.5km 全线站间距悬殊时保证站名不重叠——参考地铁线路图习惯）
 */
const evenAnchors = computed(() => {
  const centers = [...new Set(props.stations.map((station) => station.centerMeters))].sort((a, b) => a - b)
  const anchors = [0, ...centers.filter((c) => c > 0 && c < lineLength.value), lineLength.value]
  return [...new Set(anchors)].sort((a, b) => a - b)
})

function x(mileage: number): number {
  const clamped = Math.min(Math.max(mileage, 0), lineLength.value)
  const usable = VIEW_W - PAD_X * 2
  if (props.mode === 'proportional' || evenAnchors.value.length < 3) {
    return PAD_X + (clamped / lineLength.value) * usable
  }
  const anchors = evenAnchors.value
  const slotWidth = usable / (anchors.length - 1)
  let index = anchors.findIndex((anchor) => anchor >= clamped)
  if (index <= 0) return PAD_X + (index === 0 ? 0 : usable)
  const lo = anchors[index - 1]!
  const hi = anchors[index]!
  const ratio = hi === lo ? 0 : (clamped - lo) / (hi - lo)
  return PAD_X + slotWidth * (index - 1 + ratio)
}

/** 车道分配：按 track 值分组；覆盖里程最长的 track 为正线（主车道，最下方），其余向上排。渡线不占车道。 */
const orderedTracks = computed(() => {
  const lengthByTrack = new Map<string, number>()
  props.segments.forEach((segment) => {
    const track = segment.track || 'MAIN'
    if (track === CROSSOVER_TRACK) return
    lengthByTrack.set(track, (lengthByTrack.get(track) ?? 0) + (segment.endMeters - segment.startMeters))
  })
  return [...lengthByTrack.entries()].sort((a, b) => b[1] - a[1]).map(([track]) => track)
})

/* 布局随股道数动态伸缩：任意数量车道都保证画在 viewBox 内 */
const MAIN_Y = computed(() => TOP_PAD + LANE_GAP * Math.max(0, orderedTracks.value.length - 1))
const POWER_Y = computed(() => MAIN_Y.value + 42)
const VIEW_H = computed(() => POWER_Y.value + 34)

const laneByTrack = computed(() => {
  const map = new Map<string, number>()
  orderedTracks.value.forEach((track, index) => {
    map.set(track, MAIN_Y.value - LANE_GAP * index)
  })
  return map
})

const mainTrack = computed(() => {
  let best = ''
  let bestY = -Infinity
  laneByTrack.value.forEach((y, track) => {
    if (y > bestY) {
      bestY = y
      best = track
    }
  })
  return best
})

function laneY(track?: string | null): number {
  return laneByTrack.value.get(track || 'MAIN') ?? MAIN_Y.value
}

/** 最上方车道的 y（双线时为 down 股道），用于车站竖线贯穿两股道 */
const topLaneY = computed(() => {
  let minY = MAIN_Y.value
  laneByTrack.value.forEach((y) => {
    if (y < minY) minY = y
  })
  return minY
})

const segmentById = computed(() => new Map(props.segments.map((segment) => [segment.id, segment])))

/** 节点 → (车道y, x)：由非渡线段端点建立，用于渡线斜线定位 */
const nodeAnchor = computed(() => {
  const map = new Map<string, { px: number; py: number }>()
  props.segments.forEach((segment) => {
    if ((segment.track || 'MAIN') === CROSSOVER_TRACK) return
    const y = laneY(segment.track)
    if (segment.fromNode && !map.has(segment.fromNode)) map.set(segment.fromNode, { px: x(segment.startMeters), py: y })
    if (segment.toNode && !map.has(segment.toNode)) map.set(segment.toNode, { px: x(segment.endMeters), py: y })
  })
  return map
})

interface SegmentShape {
  segment: TrackSegmentState
  x1: number
  y1: number
  x2: number
  y2: number
  cx: number
  cy: number
  dimmed: boolean
  highlighted: boolean
  rawId: number | null
}

const segmentShapes = computed<SegmentShape[]>(() => {
  const highlight = props.highlightSegmentIds
  return props.segments.map((segment) => {
    const highlighted = Boolean(highlight?.includes(segment.id))
    const rawId = props.rawSegmentIds?.[segment.id] ?? null
    const dimmed = Boolean(highlight && highlight.length > 0 && !highlighted)
    if ((segment.track || 'MAIN') === CROSSOVER_TRACK) {
      // 渡线：跨车道斜线，端点取 from/to 节点在主车道上的锚点
      const fromAnchor = nodeAnchor.value.get(segment.fromNode)
      const toAnchor = nodeAnchor.value.get(segment.toNode)
      const x1 = fromAnchor?.px ?? x(segment.startMeters)
      const y1 = fromAnchor?.py ?? MAIN_Y.value
      const x2 = toAnchor?.px ?? x(segment.endMeters)
      const y2 = toAnchor?.py ?? MAIN_Y.value
      return { segment, x1, y1, x2, y2, cx: (x1 + x2) / 2, cy: (y1 + y2) / 2, highlighted, dimmed, rawId }
    }
    const y = laneY(segment.track)
    const x1 = x(segment.startMeters)
    const x2 = x(segment.endMeters)
    return { segment, x1, y1: y, x2, y2: y, cx: (x1 + x2) / 2, cy: y, highlighted, dimmed, rawId }
  })
})

/** 站台矩形：双向站台画在对应股道车道上（up 车道下缘 / 其它上缘），侧别见 tooltip */
const platformShapes = computed(() => {
  const shapes: Array<{
    key: string
    x1: number
    width: number
    y: number
    stationName: string
    track: string
    side: string | null
    center: number
  }> = []
  props.stations.forEach((station) => {
    const platforms = (station as { platforms?: Array<Record<string, unknown>> }).platforms
    if (!platforms) return
    platforms.forEach((platform) => {
      const track = String(platform.track ?? 'main')
      const lane = laneByTrack.value.get(track)
      if (lane === undefined) return
      const left = Number(platform.stopLeftMeters ?? 0)
      const right = Number(platform.stopRightMeters ?? 0)
      if (!(right > left)) return
      const px1 = x(left)
      const px2 = x(right)
      const isLowestLane = lane === MAIN_Y.value
      shapes.push({
        key: `${station.id}-${track}`,
        x1: px1,
        width: Math.max(6, px2 - px1),
        y: isLowestLane ? lane + 7 : lane - 13,
        stationName: station.name,
        track,
        side: platform.side ? String(platform.side) : null,
        center: Number(platform.centerMeters ?? 0)
      })
    })
  })
  return shapes
})

/** 跨车道连接线：同名节点出现在不同车道端点时画斜连线（分叉/汇合可视化）。 */
const connectors = computed(() => {
  interface EndPoint {
    node: string
    px: number
    py: number
  }
  const points: EndPoint[] = []
  props.segments.forEach((segment) => {
    if (!segment.fromNode && !segment.toNode) return
    const y = laneY(segment.track)
    if (segment.fromNode) points.push({ node: segment.fromNode, px: x(segment.startMeters), py: y })
    if (segment.toNode) points.push({ node: segment.toNode, px: x(segment.endMeters), py: y })
  })
  const byNode = new Map<string, EndPoint[]>()
  points.forEach((point) => {
    const list = byNode.get(point.node) ?? []
    list.push(point)
    byNode.set(point.node, list)
  })
  const lines: Array<{ key: string; x1: number; y1: number; x2: number; y2: number }> = []
  byNode.forEach((list, node) => {
    const lanes = new Set(list.map((point) => point.py))
    if (lanes.size < 2) return
    const sorted = [...list].sort((a, b) => a.py - b.py)
    for (let i = 0; i < sorted.length - 1; i++) {
      const a = sorted[i]!
      const b = sorted[i + 1]!
      if (a.py === b.py) continue
      lines.push({ key: `${node}-${i}`, x1: a.px, y1: a.py, x2: b.px, y2: b.py })
    }
  })
  return lines
})

/* ---------------- 数据图层 ---------------- */

const stationMarks = computed(() =>
  props.stations.map((station) => ({
    id: station.id,
    name: station.name,
    px: x(station.centerMeters)
  }))
)

const authorityByTrain = computed(() => new Map(props.authorities.map((authority) => [authority.trainId, authority])))

function trainLaneY(train: TrainState): number {
  const authority = authorityByTrain.value.get(train.id)
  if (authority?.currentSegmentId) {
    const segment = segmentById.value.get(authority.currentSegmentId)
    if (segment) return laneY(segment.track)
  }
  const containing = props.segments.find(
    (segment) => (segment.track || 'MAIN') === mainTrack.value && train.positionMeters >= segment.startMeters && train.positionMeters <= segment.endMeters
  )
  return containing ? laneY(containing.track) : MAIN_Y.value
}

const trainMarks = computed(() =>
  props.trains.map((train) => {
    const hasFault = Boolean(train.faultCode && train.faultCode !== 'OK')
    return {
      train,
      px: x(train.positionMeters),
      py: trainLaneY(train),
      speedKph: Math.round(train.speedMetersPerSecond * 3.6),
      hasFault,
      selected: train.id === props.selectedTrainId
    }
  })
)

const maBands = computed(() =>
  props.authorities
    .map((authority) => {
      const train = props.trains.find((item) => item.id === authority.trainId)
      if (!train) return null
      const fromX = x(train.positionMeters)
      const toX = x(authority.authorityEndMeters)
      if (!Number.isFinite(toX)) return null
      return {
        authority,
        x1: Math.min(fromX, toX),
        x2: Math.max(fromX, toX),
        endX: toX,
        y: trainLaneY(train)
      }
    })
    .filter((band): band is NonNullable<typeof band> => band !== null)
)

const signalMarks = computed(() =>
  props.signals.map((signal) => {
    const segment = segmentById.value.get(signal.segmentId)
    return {
      signal,
      px: x(signal.positionMeters),
      py: (segment ? laneY(segment.track) : MAIN_Y.value) - 16
    }
  })
)

const switchMarks = computed(() =>
  props.switches.map((switchState) => {
    // 道岔定位：以其 activeSegmentId 邻接端点为准，回退到 nodeId 匹配的区段端点
    let px: number | null = null
    let py = MAIN_Y.value
    const active = segmentById.value.get(switchState.activeSegmentId)
    const candidates = active ? [active] : props.segments
    for (const segment of candidates) {
      if (segment.fromNode === switchState.nodeId) {
        px = x(segment.startMeters)
        py = laneY(segment.track)
        break
      }
      if (segment.toNode === switchState.nodeId) {
        px = x(segment.endMeters)
        py = laneY(segment.track)
        break
      }
    }
    if (px === null && active) {
      px = x(active.startMeters)
      py = laneY(active.track)
    }
    return { switchState, px: px ?? PAD_X, py }
  })
)

const powerBands = computed(() =>
  props.powerSections.map((section) => ({
    section,
    x1: x(section.startMeters),
    x2: x(section.endMeters)
  }))
)

function occupancyClass(occupancy: string): string {
  switch (occupancy) {
    case 'OCCUPIED':
      return 'seg-occupied'
    case 'RESERVED':
      return 'seg-reserved'
    case 'FAULT':
    case 'BLOCKED':
      return 'seg-fault'
    default:
      return 'seg-free'
  }
}

function powerClass(status: string): string {
  switch (status) {
    case 'ENERGIZED':
      return 'power-energized'
    case 'LOST':
    case 'FAULT':
      return 'power-lost'
    default:
      return 'power-neutral'
  }
}
</script>

<template>
  <div class="line-diagram" :style="{ height: `${props.height}px` }">
    <svg
      :viewBox="`0 0 ${VIEW_W} ${VIEW_H}`"
      preserveAspectRatio="xMidYMid meet"
      role="img"
      aria-label="线路综合态势图（等价数据见图旁表格）"
    >
      <!-- 供电分区带 -->
      <g v-if="props.layers.power">
        <g v-for="band in powerBands" :key="band.section.id">
          <rect
            :x="band.x1"
            :y="POWER_Y"
            :width="Math.max(2, band.x2 - band.x1)"
            height="14"
            rx="2"
            :class="['power-band', powerClass(band.section.status)]"
          >
            <title>
              {{ band.section.name }} · {{ band.section.status }} · {{ Math.round(band.section.voltage) }}V ·
              {{ Math.round(band.section.loadWatts / 1000) }}kW
            </title>
          </rect>
          <text :x="(band.x1 + band.x2) / 2" :y="POWER_Y + 24" class="power-label" text-anchor="middle">
            {{ band.section.id }} {{ Math.round(band.section.voltage) }}V
          </text>
        </g>
      </g>

      <!-- 跨车道连接线 -->
      <line
        v-for="connector in connectors"
        :key="connector.key"
        :x1="connector.x1"
        :y1="connector.y1"
        :x2="connector.x2"
        :y2="connector.y2"
        class="connector"
      />

      <!-- 轨道区段 -->
      <g v-if="props.layers.track">
        <g
          v-for="shape in segmentShapes"
          :key="shape.segment.id"
          :class="{ dimmed: shape.dimmed }"
          class="segment-group"
          @click="emit('select-segment', shape.segment.id)"
        >
          <line
            :x1="shape.x1"
            :y1="shape.y1"
            :x2="shape.x2"
            :y2="shape.y2"
            :class="['segment', occupancyClass(shape.segment.occupancy), { highlighted: shape.highlighted, selected: shape.segment.id === props.selectedSegmentId }]"
          />
          <line
            v-if="shape.segment.occupancy === 'FAULT'"
            :x1="shape.x1"
            :y1="shape.y1"
            :x2="shape.x2"
            :y2="shape.y2"
            class="segment-fault-hatch blinking"
          />
          <text v-if="shape.segment.occupancy === 'FAULT'" :x="shape.cx" :y="shape.cy - 8" class="fault-icon" text-anchor="middle">⚠</text>
          <title>
            {{ shape.segment.id }} · {{ occupancyLabel(shape.segment.occupancy) }} · 限速
            {{ Math.round(shape.segment.speedLimitMetersPerSecond * 3.6) }}km/h ·
            {{ Math.round(shape.segment.startMeters) }}–{{ Math.round(shape.segment.endMeters) }}m
            <template v-if="shape.rawId">· 视景边 {{ shape.rawId }}</template>
          </title>
        </g>
      </g>

      <!-- 站台（双向：up 车道下缘 / 其它车道上缘） -->
      <g v-if="props.layers.track">
        <rect
          v-for="platform in platformShapes"
          :key="platform.key"
          :x="platform.x1"
          :y="platform.y"
          :width="platform.width"
          height="6"
          rx="1.5"
          class="platform-rect"
        >
          <title>
            {{ platform.stationName }} · {{ platform.track }} 股道站台 · 中心 {{ Math.round(platform.center) }}m ·
            站台侧 {{ platform.side ?? '—' }}
          </title>
        </rect>
      </g>

      <!-- 车站（站名画在车道带下方居中） -->
      <g v-for="station in stationMarks" :key="station.id">
        <line :x1="station.px" :y1="topLaneY - 10" :x2="station.px" :y2="MAIN_Y + 10" class="station-tick" />
        <circle :cx="station.px" :cy="MAIN_Y" r="4.5" class="station-dot" />
        <circle v-if="topLaneY !== MAIN_Y" :cx="station.px" :cy="topLaneY" r="4.5" class="station-dot" />
        <text :x="station.px" :y="MAIN_Y + 30" class="station-label" text-anchor="middle">{{ station.name }}</text>
      </g>

      <!-- 移动授权带 -->
      <g v-if="props.layers.ma">
        <g v-for="band in maBands" :key="`ma-${band.authority.trainId}`">
          <line :x1="band.x1" :y1="band.y - 9" :x2="band.x2" :y2="band.y - 9" class="ma-band">
            <title>
              {{ band.authority.trainId }} MA 至 {{ Math.round(band.authority.authorityEndMeters) }}m · 授权限速
              {{ Math.round(band.authority.speedLimitMetersPerSecond * 3.6) }}km/h · {{ band.authority.reason }}
            </title>
          </line>
          <path :d="`M ${band.endX} ${band.y - 13} L ${band.endX} ${band.y - 5} L ${band.endX + 5} ${band.y - 9} Z`" class="ma-end" />
        </g>
      </g>

      <!-- 信号机：颜色 + 形状双通道（绿⬤ / 黄▲ / 红■） -->
      <g v-if="props.layers.signals">
        <g v-for="mark in signalMarks" :key="mark.signal.signalId">
          <line :x1="mark.px" :y1="mark.py + 4" :x2="mark.px" :y2="mark.py + 14" class="signal-pole" />
          <circle v-if="mark.signal.aspect === 'GREEN'" :cx="mark.px" :cy="mark.py" r="5" class="signal green" />
          <path
            v-else-if="mark.signal.aspect === 'YELLOW'"
            :d="`M ${mark.px} ${mark.py - 6} L ${mark.px - 5.5} ${mark.py + 4} L ${mark.px + 5.5} ${mark.py + 4} Z`"
            class="signal yellow"
          />
          <rect v-else :x="mark.px - 4.5" :y="mark.py - 4.5" width="9" height="9" class="signal red" />
          <title>
            {{ mark.signal.signalId }} · {{ signalAspectLabel(mark.signal.aspect) }}
            <template v-if="mark.signal.reasonTrainId">（原因车 {{ mark.signal.reasonTrainId }}）</template>
          </title>
        </g>
      </g>

      <!-- 道岔 -->
      <g v-if="props.layers.switches">
        <g v-for="mark in switchMarks" :key="mark.switchState.id">
          <path
            :d="`M ${mark.px} ${mark.py - 7} L ${mark.px + 7} ${mark.py} L ${mark.px} ${mark.py + 7} L ${mark.px - 7} ${mark.py} Z`"
            class="switch-node"
          />
          <text :x="mark.px" :y="mark.py - 11" class="switch-label" text-anchor="middle">
            {{ mark.switchState.id }} {{ mark.switchState.position === 'REVERSE' ? '反' : '定' }}{{ mark.switchState.locked ? '·锁' : '' }}
          </text>
          <title>
            {{ mark.switchState.id }} · {{ switchPositionLabel(mark.switchState.position) }} ·
            {{ mark.switchState.locked ? '已锁闭' : '未锁闭' }} · 开通 {{ mark.switchState.activeSegmentId }}
          </title>
        </g>
      </g>

      <!-- 列车 -->
      <g v-if="props.layers.trains">
        <g
          v-for="mark in trainMarks"
          :key="mark.train.id"
          class="train-group"
          @click.stop="emit('select-train', mark.train.id)"
        >
          <rect
            :x="mark.px - 11"
            :y="mark.py - 7"
            width="22"
            height="12"
            rx="4"
            :class="['train-body', { fault: mark.hasFault, selected: mark.selected }]"
          />
          <text v-if="mark.hasFault" :x="mark.px" :y="mark.py + 3.5" class="train-fault-mark" text-anchor="middle">⚠</text>
          <text :x="mark.px" :y="mark.py - 12" class="train-label" text-anchor="middle">
            {{ mark.train.id }} <tspan class="train-speed">{{ mark.speedKph }}km/h</tspan>
          </text>
          <title>
            {{ mark.train.id }}（{{ mark.train.serviceNo || '—' }}）· {{ mark.speedKph }}km/h · 模式
            {{ mark.train.operationMode }} · 里程 {{ Math.round(mark.train.positionMeters) }}m
            <template v-if="mark.hasFault">· 故障 {{ mark.train.faultCode }}</template>
          </title>
        </g>
      </g>
    </svg>
  </div>
</template>

<style scoped>
.line-diagram {
  background: var(--bg-inset);
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  overflow: hidden;
}

svg {
  width: 100%;
  height: 100%;
  display: block;
}

.segment-group { cursor: pointer; }
.segment-group.dimmed { opacity: 0.25; }

.segment {
  stroke-width: 6;
  stroke-linecap: round;
}
.segment.seg-free { stroke: var(--track-free); }
.segment.seg-reserved { stroke: var(--track-reserved); }
.segment.seg-occupied { stroke: var(--track-occupied); }
.segment.seg-fault { stroke: var(--track-fault); }
.segment.highlighted { stroke-width: 9; filter: drop-shadow(0 0 4px rgba(76, 141, 255, 0.8)); }
.segment.selected { filter: drop-shadow(0 0 5px rgba(76, 141, 255, 0.9)); }

.segment-fault-hatch {
  stroke: rgba(255, 255, 255, 0.65);
  stroke-width: 6;
  stroke-dasharray: 3 7;
  stroke-linecap: butt;
  pointer-events: none;
}

.fault-icon {
  font-size: 11px;
  fill: var(--track-fault);
  pointer-events: none;
}

.connector {
  stroke: var(--border-strong);
  stroke-width: 2.5;
  stroke-dasharray: 1 0;
}

.station-tick { stroke: var(--text-muted); stroke-width: 1.5; stroke-dasharray: 2 3; }
.station-dot { fill: var(--bg-inset); stroke: var(--text-secondary); stroke-width: 2; }
.station-label { fill: var(--text-secondary); font-size: 11px; }

.platform-rect {
  fill: var(--accent-muted);
  stroke: var(--accent);
  stroke-width: 1;
}

.ma-band {
  stroke: var(--ma-band);
  stroke-width: 5;
  stroke-linecap: butt;
}
.ma-end { fill: var(--status-ok); }

.signal-pole { stroke: var(--text-muted); stroke-width: 1.5; }
.signal.green { fill: var(--signal-green); }
.signal.yellow { fill: var(--signal-yellow); }
.signal.red { fill: var(--signal-red); }

.switch-node {
  fill: var(--switch-node);
  fill-opacity: 0.85;
  stroke: var(--bg-inset);
  stroke-width: 1;
}
.switch-label { fill: var(--switch-node); font-size: 9px; }

.train-group { cursor: pointer; }
.train-body {
  fill: var(--train-body);
  stroke: var(--bg-inset);
  stroke-width: 1.5;
}
.train-body.fault { fill: var(--train-fault); }
.train-body.selected { stroke: var(--accent); stroke-width: 2.5; }
.train-fault-mark { font-size: 9px; fill: #fff; pointer-events: none; }
.train-label { fill: var(--text-primary); font-size: 10px; font-weight: 600; }
.train-speed { fill: var(--text-secondary); font-weight: 400; }

.power-band { opacity: 0.9; }
.power-band.power-energized { fill: var(--power-energized); }
.power-band.power-lost { fill: var(--power-lost); }
.power-band.power-neutral { fill: var(--status-neutral-bg); }
.power-label { fill: var(--text-muted); font-size: 9px; }
</style>
