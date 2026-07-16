<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import * as echarts from 'echarts'
import type { DispatchTrainProfile } from '../../types/dispatch'

const props = defineProps<{
  profiles: DispatchTrainProfile[]
  targetHeadwaySeconds: number
}>()

const chartRef = ref<HTMLDivElement | null>(null)
let chart: echarts.ECharts | null = null

function render() {
  if (!chartRef.value) return
  if (!chart) {
    chart = echarts.init(chartRef.value)
  }
  const trainIds = props.profiles.map((item) => item.trainId)
  const actual = props.profiles.map((item) => ({
    value: item.headwayActualSeconds,
    itemStyle: {
      color: item.regulationAction === 'CATCH_UP'
        ? '#f59e0b'
        : item.regulationAction === 'SLOW_DOWN'
          ? '#ef4444'
          : item.regulationAction === 'NORMAL'
            ? '#10b981'
            : '#94a3b8'
    }
  }))
  const target = props.profiles.map(() => props.targetHeadwaySeconds)
  chart.setOption({
    tooltip: {
      trigger: 'axis',
      formatter: (items: unknown) => {
        const values = Array.isArray(items) ? items as Array<{ dataIndex: number; seriesName: string; value: number }> : []
        const index = values[0]?.dataIndex ?? 0
        const profile = props.profiles[index]
        const actualText = profile?.headwayActualSeconds === null ? '-' : `${profile?.headwayActualSeconds.toFixed(0)} 秒`
        const error = profile?.headwayErrorSeconds
        const errorText = error === null || error === undefined ? '-' : `${error > 0 ? '+' : ''}${error.toFixed(0)} 秒`
        return `${profile?.trainId ?? '-'}<br/>实际间隔：${actualText}<br/>间隔偏差：${errorText}<br/>本车动作：${profile?.regulationAction ?? 'OBSERVE'}`
      }
    },
    legend: { data: ['实际间隔', '目标间隔'], textStyle: { color: '#9aa7ba' } },
    grid: { left: 40, right: 16, top: 40, bottom: 30 },
    xAxis: {
      type: 'category',
      data: trainIds,
      axisLabel: { color: '#9aa7ba' },
      axisLine: { lineStyle: { color: '#3a475f' } }
    },
    yAxis: {
      type: 'value',
      name: '秒',
      nameTextStyle: { color: '#9aa7ba' },
      axisLabel: { color: '#9aa7ba' },
      splitLine: { lineStyle: { color: '#283246' } }
    },
    series: [
      { name: '实际间隔', type: 'bar', data: actual },
      { name: '目标间隔', type: 'line', data: target, itemStyle: { color: '#94a3b8' } }
    ]
  })
}

onMounted(render)
watch(() => [props.profiles, props.targetHeadwaySeconds], render, { deep: true })
onBeforeUnmount(() => {
  chart?.dispose()
  chart = null
})
</script>

<template>
  <section class="panel">
    <header>
      <h2>本车发车间隔与目标对比</h2>
      <span>柱高=实际间隔，颜色=本车调节动作</span>
    </header>
    <div class="action-legend" aria-label="调节动作颜色说明">
      <span><i class="normal" />正常</span>
      <span><i class="slow" />本车放慢/拉大间隔</span>
      <span><i class="catch" />本车追赶/压缩间隔</span>
      <span><i class="observe" />继续观测</span>
    </div>
    <div ref="chartRef" class="chart" />
  </section>
</template>

<style scoped>
.panel {
  background: var(--bg-panel);
  border: 1px solid var(--border);
  border-radius: 12px;
  padding: 16px;
}

header {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  align-items: baseline;
}

h2 {
  margin: 0;
  font-size: 16px;
}

header span {
  color: var(--text-secondary);
  font-size: 12px;
}

.action-legend {
  display: flex;
  flex-wrap: wrap;
  gap: 8px 12px;
  margin-top: 10px;
  color: var(--text-secondary);
  font-size: 12px;
}

.action-legend span {
  display: inline-flex;
  align-items: center;
  gap: 5px;
}

.action-legend i {
  width: 9px;
  height: 9px;
  border-radius: 2px;
}

.action-legend .normal {
  background: #10b981;
}

.action-legend .slow {
  background: #ef4444;
}

.action-legend .catch {
  background: #f59e0b;
}

.action-legend .observe {
  background: #94a3b8;
}

.chart {
  width: 100%;
  height: 232px;
}
</style>
