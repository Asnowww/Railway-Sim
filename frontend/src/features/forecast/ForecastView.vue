<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import * as echarts from 'echarts'
import { useTopologyStore } from '../../stores/topology'
import Panel from '../../shared/components/Panel.vue'
import StatusBadge from '../../shared/components/StatusBadge.vue'
import {
  BUCKET_MINUTES,
  buildStationProfile,
  minuteToLabel,
  todaySeed,
  type StationFlowProfile
} from './passengerModel'

const topology = useTopologyStore()

/** 无拓扑时的兜底站表（同样明确标注为模拟对象） */
const FALLBACK_STATIONS = [
  { id: 'S01', name: '一号站' },
  { id: 'S02', name: '二号站' },
  { id: 'S03', name: '三号站' },
  { id: 'S04', name: '四号站' },
  { id: 'S05', name: '五号站' }
]

const stations = computed(() =>
  topology.stations.length > 0 ? topology.stations.map((s) => ({ id: s.id, name: s.name })) : FALLBACK_STATIONS
)

const now = ref(new Date())
let clockTimer = 0

const nowMinute = computed(() => {
  const bucket = Math.floor((now.value.getHours() * 60 + now.value.getMinutes()) / BUCKET_MINUTES) * BUCKET_MINUTES
  return Math.max(bucket, 6 * 60)
})

const profiles = computed<StationFlowProfile[]>(() => {
  const seed = todaySeed(now.value)
  return stations.value.map((station) => buildStationProfile(station.id, station.name, nowMinute.value, seed))
})

const selectedStationId = ref('')
watch(
  stations,
  (list) => {
    if (!selectedStationId.value && list.length > 0) selectedStationId.value = list[0]!.id
  },
  { immediate: true }
)

const selectedProfile = computed(
  () => profiles.value.find((profile) => profile.stationId === selectedStationId.value) ?? profiles.value[0] ?? null
)

/* ---------- 全网 KPI ---------- */

const networkCurrent = computed(() =>
  profiles.value.reduce((sum, profile) => sum + profile.currentInbound + profile.currentOutbound, 0)
)
const busiest = computed(() =>
  profiles.value.length === 0 ? null : [...profiles.value].sort((a, b) => b.loadPercent - a.loadPercent)[0]!
)
const risingCount = computed(() => profiles.value.filter((profile) => profile.trend === 1).length)
const overloadedCount = computed(() => profiles.value.filter((profile) => profile.loadPercent >= 90).length)

function loadTone(percent: number) {
  if (percent >= 100) return 'danger' as const
  if (percent >= 80) return 'warn' as const
  if (percent >= 50) return 'info' as const
  return 'ok' as const
}

function trendGlyph(trend: -1 | 0 | 1): string {
  return trend === 1 ? '↗ 上升' : trend === -1 ? '↘ 回落' : '→ 平稳'
}

/* ---------- 主图：历史 + 预测带 ---------- */

const chartRef = ref<HTMLDivElement | null>(null)
let chart: echarts.ECharts | null = null
let resizeObserver: ResizeObserver | null = null

function renderChart(): void {
  if (!chartRef.value || !selectedProfile.value) return
  if (!chart) chart = echarts.init(chartRef.value)
  const profile = selectedProfile.value

  const historyLabels = profile.history.map((point) => minuteToLabel(point.minute))
  const forecastLabels = profile.forecast.map((point) => minuteToLabel(point.minute))
  const labels = [...historyLabels, ...forecastLabels]
  const historyTotal = profile.history.map((point) => point.inbound + point.outbound)
  const gap = new Array<number | null>(Math.max(historyTotal.length - 1, 0)).fill(null)
  const expected = [...gap, historyTotal[historyTotal.length - 1] ?? null, ...profile.forecast.map((p) => p.expected)]
  const lower = [...gap, historyTotal[historyTotal.length - 1] ?? null, ...profile.forecast.map((p) => p.lower)]
  const bandWidth = [...gap, 0, ...profile.forecast.map((p) => p.upper - p.lower)]

  chart.setOption(
    {
      backgroundColor: 'transparent',
      tooltip: {
        trigger: 'axis',
        backgroundColor: '#1c2534',
        borderColor: '#3a475f',
        textStyle: { color: '#e6ebf3', fontSize: 12 }
      },
      legend: {
        data: ['进站', '出站', '预测客流', '预测区间'],
        textStyle: { color: '#9aa7ba' },
        top: 2
      },
      grid: { left: 52, right: 20, top: 34, bottom: 46 },
      xAxis: {
        type: 'category',
        data: labels,
        boundaryGap: false,
        axisLabel: { color: '#9aa7ba', interval: 7 },
        axisLine: { lineStyle: { color: '#3a475f' } }
      },
      yAxis: {
        type: 'value',
        name: '人/15min',
        nameTextStyle: { color: '#9aa7ba' },
        axisLabel: { color: '#9aa7ba' },
        splitLine: { lineStyle: { color: '#283246' } }
      },
      series: [
        {
          name: '进站',
          type: 'line',
          smooth: true,
          symbol: 'none',
          data: profile.history.map((point) => point.inbound),
          lineStyle: { width: 2, color: '#4c8dff' },
          areaStyle: {
            color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
              { offset: 0, color: 'rgba(76,141,255,0.35)' },
              { offset: 1, color: 'rgba(76,141,255,0.02)' }
            ])
          }
        },
        {
          name: '出站',
          type: 'line',
          smooth: true,
          symbol: 'none',
          data: profile.history.map((point) => point.outbound),
          lineStyle: { width: 2, color: '#34d17b' },
          areaStyle: {
            color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
              { offset: 0, color: 'rgba(52,209,123,0.28)' },
              { offset: 1, color: 'rgba(52,209,123,0.02)' }
            ])
          }
        },
        {
          name: '预测区间下界',
          type: 'line',
          data: lower,
          symbol: 'none',
          stack: 'band',
          lineStyle: { opacity: 0 },
          tooltip: { show: false }
        },
        {
          name: '预测区间',
          type: 'line',
          data: bandWidth,
          symbol: 'none',
          stack: 'band',
          lineStyle: { opacity: 0 },
          areaStyle: { color: 'rgba(245,184,61,0.18)' }
        },
        {
          name: '预测客流',
          type: 'line',
          smooth: true,
          data: expected,
          symbol: 'circle',
          symbolSize: 5,
          lineStyle: { width: 2.5, color: '#f5b83d', type: 'dashed' },
          itemStyle: { color: '#f5b83d' }
        }
      ],
      graphic: historyLabels.length > 0
        ? [
            {
              type: 'text',
              right: 24,
              top: 40,
              style: { text: '← 实测(模拟)　|　预测 →', fill: '#66738a', fontSize: 11 }
            }
          ]
        : []
    },
    { notMerge: true }
  )
}

watch([selectedProfile], () => renderChart())

onMounted(() => {
  void topology.load()
  renderChart()
  clockTimer = window.setInterval(() => (now.value = new Date()), 60_000)
  resizeObserver = new ResizeObserver(() => chart?.resize())
  if (chartRef.value) resizeObserver.observe(chartRef.value)
})

onBeforeUnmount(() => {
  window.clearInterval(clockTimer)
  resizeObserver?.disconnect()
  chart?.dispose()
  chart = null
})

const ranking = computed(() => [...profiles.value].sort((a, b) => b.loadPercent - a.loadPercent))
</script>

<template>
  <div class="forecast">
    <div class="sim-banner" role="note">
      <span class="sim-badge">模拟数据</span>
      本工作台的客流与预测全部由前端确定性模型合成（站点取自真实线路拓扑），仅用于展示预测交互形态；后端目前没有客流数据源。
    </div>

    <div class="kpi-row">
      <div class="kpi">
        <span class="kpi-label">全网当前客流（15min）</span>
        <strong class="num">{{ networkCurrent.toLocaleString() }}</strong>
        <span class="kpi-unit">人次</span>
      </div>
      <div class="kpi">
        <span class="kpi-label">最繁忙车站</span>
        <strong>{{ busiest?.stationName ?? '—' }}</strong>
        <StatusBadge v-if="busiest" :tone="loadTone(busiest.loadPercent)" :label="`${busiest.loadPercent}%`" />
      </div>
      <div class="kpi">
        <span class="kpi-label">未来 1h 客流上升车站</span>
        <strong class="num">{{ risingCount }}</strong>
        <span class="kpi-unit">/ {{ profiles.length }}</span>
      </div>
      <div :class="['kpi', { alert: overloadedCount > 0 }]">
        <span class="kpi-label">负荷 ≥90% 车站</span>
        <strong class="num">{{ overloadedCount }}</strong>
      </div>
    </div>

    <div class="main-grid">
      <Panel
        :title="`${selectedProfile?.stationName ?? '—'} · 客流趋势与预测`"
        :subtitle="`15 分钟颗粒 · 预测未来 2 小时（含置信区间）· 当前 ${minuteToLabel(nowMinute)}`"
      >
        <template #actions>
          <select v-model="selectedStationId" aria-label="选择车站">
            <option v-for="station in stations" :key="station.id" :value="station.id">{{ station.name }}</option>
          </select>
        </template>
        <div ref="chartRef" class="main-chart" aria-label="客流历史与预测图（模拟数据）"></div>
        <div v-if="selectedProfile" class="chart-footnotes">
          <span>当前进站 <strong class="num">{{ selectedProfile.currentInbound }}</strong></span>
          <span>当前出站 <strong class="num">{{ selectedProfile.currentOutbound }}</strong></span>
          <span>趋势 <strong>{{ trendGlyph(selectedProfile.trend) }}</strong></span>
          <span>典型峰值 <strong class="num">{{ minuteToLabel(selectedProfile.peakMinute) }}</strong></span>
        </div>
      </Panel>

      <Panel title="车站负荷热力" subtitle="当前 15 分钟负荷（占车站通过能力）" flush>
        <div class="heat-grid scroll-y">
          <button
            v-for="profile in profiles"
            :key="profile.stationId"
            type="button"
            :class="['heat-tile', `tone-${loadTone(profile.loadPercent)}`, { active: profile.stationId === selectedStationId }]"
            @click="selectedStationId = profile.stationId"
          >
            <span class="tile-name">{{ profile.stationName }}</span>
            <strong class="num tile-load">{{ profile.loadPercent }}%</strong>
            <span class="tile-trend">{{ trendGlyph(profile.trend) }}</span>
            <span class="tile-flow num">进 {{ profile.currentInbound }} · 出 {{ profile.currentOutbound }}</span>
          </button>
        </div>
      </Panel>
    </div>

    <Panel title="车站负荷排行（模拟）" flush>
      <div class="ranking">
        <div v-for="profile in ranking" :key="profile.stationId" class="rank-row">
          <span class="rank-name">{{ profile.stationName }}</span>
          <div class="rank-bar-track">
            <div
              :class="['rank-bar', `tone-${loadTone(profile.loadPercent)}`]"
              :style="{ width: `${Math.min(profile.loadPercent, 120) / 1.2}%` }"
            ></div>
          </div>
          <span class="rank-value num">{{ profile.loadPercent }}%</span>
          <StatusBadge :tone="loadTone(profile.loadPercent)" :label="profile.loadPercent >= 100 ? '超负荷' : profile.loadPercent >= 80 ? '拥挤' : profile.loadPercent >= 50 ? '繁忙' : '正常'" />
        </div>
      </div>
    </Panel>
  </div>
</template>

<style scoped>
.forecast {
  display: flex;
  flex-direction: column;
  gap: var(--gap-lg);
}

.sim-banner {
  display: flex;
  align-items: center;
  gap: var(--gap-md);
  background: var(--status-warn-bg);
  border: 1px solid var(--status-warn);
  border-radius: var(--radius-lg);
  padding: 8px 14px;
  color: var(--text-secondary);
  font-size: var(--fs-sm);
}

.sim-badge {
  flex: none;
  background: var(--status-warn);
  color: var(--text-inverse);
  font-weight: 800;
  font-size: var(--fs-xs);
  padding: 2px 10px;
  border-radius: 999px;
}

.kpi-row {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
  gap: var(--gap-md);
}

.kpi {
  background: var(--bg-panel);
  border: 1px solid var(--border);
  border-radius: var(--radius-lg);
  padding: 12px 16px;
  display: flex;
  align-items: baseline;
  gap: 8px;
  flex-wrap: wrap;
}

.kpi.alert { border-color: var(--status-danger); background: var(--status-danger-bg); }
.kpi-label { flex-basis: 100%; font-size: var(--fs-xs); color: var(--text-secondary); }
.kpi strong { font-size: var(--fs-xl); line-height: 1; }
.kpi-unit { font-size: var(--fs-sm); color: var(--text-muted); }

.main-grid {
  display: grid;
  grid-template-columns: minmax(0, 1.5fr) minmax(0, 1fr);
  gap: var(--gap-lg);
  align-items: stretch;
}

@media (max-width: 1100px) {
  .main-grid { grid-template-columns: 1fr; }
}

.main-chart {
  width: 100%;
  height: 340px;
}

.chart-footnotes {
  display: flex;
  gap: var(--gap-lg);
  flex-wrap: wrap;
  font-size: var(--fs-sm);
  color: var(--text-secondary);
}

.heat-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(150px, 1fr));
  gap: var(--gap-md);
  padding: 12px 14px;
  max-height: 400px;
}

.heat-tile {
  border-radius: var(--radius-md);
  border: 1px solid var(--border);
  background: var(--bg-panel-raised);
  padding: 10px 12px;
  display: flex;
  flex-direction: column;
  gap: 3px;
  color: var(--text-primary);
  cursor: pointer;
  text-align: left;
}

.heat-tile.active { outline: 2px solid var(--accent); }
.heat-tile.tone-danger { background: var(--status-danger-bg); border-color: var(--status-danger); }
.heat-tile.tone-warn { background: var(--status-warn-bg); border-color: var(--status-warn); }
.heat-tile.tone-info { background: var(--status-info-bg); border-color: var(--status-info); }

.tile-name { font-size: var(--fs-sm); font-weight: 600; }
.tile-load { font-size: 22px; }
.tile-trend { font-size: var(--fs-xs); color: var(--text-secondary); }
.tile-flow { font-size: var(--fs-xs); color: var(--text-muted); }

.ranking {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 12px 14px;
}

.rank-row {
  display: grid;
  grid-template-columns: 120px 1fr 52px auto;
  align-items: center;
  gap: var(--gap-md);
}

.rank-name { font-size: var(--fs-sm); }

.rank-bar-track {
  height: 10px;
  background: var(--bg-inset);
  border-radius: 999px;
  overflow: hidden;
}

.rank-bar { height: 100%; border-radius: 999px; }
.rank-bar.tone-ok { background: var(--status-ok); }
.rank-bar.tone-info { background: var(--status-info); }
.rank-bar.tone-warn { background: var(--status-warn); }
.rank-bar.tone-danger { background: var(--status-danger); }

.rank-value { text-align: right; font-size: var(--fs-sm); }
</style>
