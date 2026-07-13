<script setup lang="ts">
import { computed } from 'vue'

/**
 * CBTC/ETCS 风格圆形速度表。
 * 全部由后端真实数据驱动：当前速度、允许速度(ATP限速)、目标速度(ATO)。
 * 双通道：颜色 + 数字 + 指针位置，超速时红色闪烁边框（不仅靠色）。
 */
const props = withDefaults(
  defineProps<{
    /** 当前速度 km/h */
    speed: number
    /** ATP 允许速度 km/h（监督速度上限） */
    permitted: number
    /** ATO 目标速度 km/h（可选，画一个小三角标记） */
    target?: number | null
    /** 量程上限；默认取 permitted 上浮到 10 的倍数、且 ≥ speed */
    maxSpeed?: number | null
    size?: number
  }>(),
  { target: null, maxSpeed: null, size: 300 }
)

const START_ANGLE = -225 // 起点（左下）
const SWEEP = 270 // 总扫过角度
const R = 140
const CX = 160
const CY = 160

const scaleMax = computed(() => {
  if (props.maxSpeed && props.maxSpeed > 0) return props.maxSpeed
  const base = Math.max(props.permitted, props.speed, 40)
  return Math.ceil((base + 5) / 20) * 20
})

function valueToAngle(v: number): number {
  const clamped = Math.min(Math.max(v, 0), scaleMax.value)
  return START_ANGLE + (clamped / scaleMax.value) * SWEEP
}

function polar(angleDeg: number, radius: number): { x: number; y: number } {
  const a = (angleDeg * Math.PI) / 180
  return { x: CX + radius * Math.cos(a), y: CY + radius * Math.sin(a) }
}

/** 生成弧线 path（从 v1 到 v2，半径 radius） */
function arcPath(v1: number, v2: number, radius: number): string {
  const a1 = valueToAngle(v1)
  const a2 = valueToAngle(v2)
  const p1 = polar(a1, radius)
  const p2 = polar(a2, radius)
  const largeArc = Math.abs(a2 - a1) > 180 ? 1 : 0
  return `M ${p1.x.toFixed(2)} ${p1.y.toFixed(2)} A ${radius} ${radius} 0 ${largeArc} 1 ${p2.x.toFixed(2)} ${p2.y.toFixed(2)}`
}

const trackPath = computed(() => arcPath(0, scaleMax.value, R))
const permittedPath = computed(() => arcPath(0, Math.min(props.permitted, scaleMax.value), R))
const overspeedPath = computed(() =>
  props.permitted < scaleMax.value ? arcPath(props.permitted, scaleMax.value, R) : ''
)

const needle = computed(() => {
  const a = valueToAngle(props.speed)
  const tip = polar(a, R - 12)
  const tail = polar(a + 180, 22)
  return { tip, tail }
})

const targetMark = computed(() => {
  if (props.target === null || props.target === undefined || props.target <= 0) return null
  const a = valueToAngle(props.target)
  const outer = polar(a, R + 2)
  const inner = polar(a, R - 18)
  // 小三角
  const left = polar(a - 1.6, R + 14)
  const right = polar(a + 1.6, R + 14)
  const pt = polar(a, R + 2)
  return { outer, inner, tri: `${left.x},${left.y} ${right.x},${right.y} ${pt.x},${pt.y}` }
})

const ticks = computed(() => {
  const step = scaleMax.value > 120 ? 20 : 10
  const arr: Array<{ x1: number; y1: number; x2: number; y2: number; lx: number; ly: number; label: number; major: boolean }> = []
  for (let v = 0; v <= scaleMax.value; v += step) {
    const a = valueToAngle(v)
    const outer = polar(a, R)
    const inner = polar(a, R - 12)
    const labelPos = polar(a, R - 28)
    arr.push({ x1: inner.x, y1: inner.y, x2: outer.x, y2: outer.y, lx: labelPos.x, ly: labelPos.y, label: v, major: true })
  }
  return arr
})

/** 超速状态：当前速度超过允许速度 */
const overspeed = computed(() => props.speed > props.permitted + 0.5)
const nearLimit = computed(() => !overspeed.value && props.speed > props.permitted - 5)

const readoutColor = computed(() =>
  overspeed.value ? 'var(--status-danger)' : nearLimit.value ? 'var(--status-warn)' : 'var(--status-ok)'
)
</script>

<template>
  <div class="speed-gauge" :class="{ overspeed }" :style="{ width: `${props.size}px`, height: `${props.size}px` }">
    <svg viewBox="0 0 320 320" role="img" :aria-label="`当前速度 ${Math.round(props.speed)} 公里每小时，允许 ${Math.round(props.permitted)}`">
      <!-- 底环 -->
      <path :d="trackPath" class="arc-track" />
      <!-- 允许速度区（绿） -->
      <path :d="permittedPath" class="arc-permitted" />
      <!-- 超速区（红） -->
      <path v-if="overspeedPath" :d="overspeedPath" class="arc-overspeed" />

      <!-- 刻度 -->
      <g class="ticks">
        <template v-for="t in ticks" :key="t.label">
          <line :x1="t.x1" :y1="t.y1" :x2="t.x2" :y2="t.y2" class="tick" />
          <text :x="t.lx" :y="t.ly" class="tick-label" text-anchor="middle" dominant-baseline="middle">{{ t.label }}</text>
        </template>
      </g>

      <!-- ATO 目标速度标记 -->
      <polygon v-if="targetMark" :points="targetMark.tri" class="target-mark" />

      <!-- 指针 -->
      <line :x1="needle.tail.x" :y1="needle.tail.y" :x2="needle.tip.x" :y2="needle.tip.y" class="needle" :class="{ over: overspeed }" />
      <circle :cx="CX" :cy="CY" r="10" class="needle-hub" />

      <!-- 中心数字 -->
      <text :x="CX" :y="CY - 4" class="readout" :style="{ fill: readoutColor }" text-anchor="middle">{{ Math.round(props.speed) }}</text>
      <text :x="CX" :y="CY + 22" class="readout-unit" text-anchor="middle">km/h</text>
    </svg>
    <div v-if="overspeed" class="overspeed-flag blinking">超速</div>
  </div>
</template>

<style scoped>
.speed-gauge {
  position: relative;
  display: grid;
  place-items: center;
}

svg {
  width: 100%;
  height: 100%;
}

.arc-track {
  fill: none;
  stroke: var(--bg-inset);
  stroke-width: 16;
  stroke-linecap: round;
}

.arc-permitted {
  fill: none;
  stroke: var(--status-ok);
  stroke-width: 16;
  stroke-linecap: round;
  opacity: 0.85;
}

.arc-overspeed {
  fill: none;
  stroke: var(--status-danger);
  stroke-width: 16;
  stroke-linecap: butt;
  opacity: 0.75;
}

.tick {
  stroke: var(--text-muted);
  stroke-width: 1.5;
}

.tick-label {
  fill: var(--text-secondary);
  font-size: 12px;
  font-family: var(--font-mono);
}

.target-mark {
  fill: var(--accent);
  stroke: #fff;
  stroke-width: 0.5;
}

.needle {
  stroke: var(--text-primary);
  stroke-width: 4;
  stroke-linecap: round;
  transition: all 0.4s cubic-bezier(0.22, 1, 0.36, 1);
}

.needle.over {
  stroke: var(--status-danger);
}

.needle-hub {
  fill: var(--text-primary);
  stroke: var(--bg-panel);
  stroke-width: 2;
}

.readout {
  font-size: 58px;
  font-weight: 800;
  font-family: var(--font-mono);
  font-variant-numeric: tabular-nums;
}

.readout-unit {
  fill: var(--text-muted);
  font-size: 15px;
}

.overspeed-flag {
  position: absolute;
  bottom: 18%;
  left: 50%;
  transform: translateX(-50%);
  background: var(--status-danger);
  color: #fff;
  font-weight: 800;
  font-size: var(--fs-sm);
  padding: 2px 12px;
  border-radius: 4px;
  letter-spacing: 0.15em;
}

.speed-gauge.overspeed {
  border-radius: 50%;
  box-shadow: 0 0 0 3px var(--status-danger), 0 0 24px rgba(240, 86, 79, 0.5);
}
</style>
