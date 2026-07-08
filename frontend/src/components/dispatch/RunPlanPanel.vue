<script setup lang="ts">
import type { RunPlanPeriod, RunPlanResponse } from '../../types/dispatch'

defineProps<{
  plan: RunPlanResponse | null
  runMode: string
  targetHeadwaySeconds: number
}>()

function label(type: string) {
  const map: Record<string, string> = {
    PEAK: '高峰',
    FLAT: '平峰',
    OFF_PEAK: '低谷'
  }
  return map[type] ?? type
}
</script>

<template>
  <section class="panel">
    <header>
      <h2>运行计划</h2>
      <span class="badge">{{ label(runMode) }}</span>
    </header>
    <p class="summary">当前目标发车间隔：<strong>{{ targetHeadwaySeconds }}s</strong></p>
    <table v-if="plan">
      <thead>
        <tr>
          <th>时段</th>
          <th>时间</th>
          <th>间隔(s)</th>
          <th>停站(s)</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="period in plan.periods" :key="period.periodType + period.start">
          <td>{{ label(period.periodType) }}</td>
          <td>{{ period.start }} - {{ period.end }}</td>
          <td>{{ period.departureIntervalSec }}</td>
          <td>{{ period.defaultDwellTimeSec }}</td>
        </tr>
      </tbody>
    </table>
  </section>
</template>

<style scoped>
.panel {
  background: #fff;
  border: 1px solid #e2e8f0;
  border-radius: 12px;
  padding: 16px;
}

header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 8px;
}

h2 {
  margin: 0;
  font-size: 16px;
}

.badge {
  background: #dbeafe;
  color: #1d4ed8;
  padding: 4px 10px;
  border-radius: 999px;
  font-size: 12px;
}

.summary {
  margin: 0 0 12px;
  color: #64748b;
}

table {
  width: 100%;
  border-collapse: collapse;
  font-size: 13px;
}

th,
td {
  border-bottom: 1px solid #f1f5f9;
  padding: 8px 6px;
  text-align: left;
}
</style>
