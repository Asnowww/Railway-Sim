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
  const actual = props.profiles.map((item) => item.headwayActualSeconds ?? 0)
  const target = props.profiles.map(() => props.targetHeadwaySeconds)
  chart.setOption({
    tooltip: { trigger: 'axis' },
    legend: { data: ['实际间隔', '目标间隔'] },
    grid: { left: 40, right: 16, top: 40, bottom: 30 },
    xAxis: { type: 'category', data: trainIds },
    yAxis: { type: 'value', name: '秒' },
    series: [
      { name: '实际间隔', type: 'bar', data: actual, itemStyle: { color: '#3b82f6' } },
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
    <h2>发车间隔对比</h2>
    <div ref="chartRef" class="chart" />
  </section>
</template>

<style scoped>
.panel {
  background: #fff;
  border: 1px solid #e2e8f0;
  border-radius: 12px;
  padding: 16px;
}

h2 {
  margin: 0 0 8px;
  font-size: 16px;
}

.chart {
  width: 100%;
  height: 260px;
}
</style>
