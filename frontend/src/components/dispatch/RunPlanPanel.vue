<script setup lang="ts">
import { computed } from 'vue'
import type { RunPlanPeriod, RunPlanResponse } from '../../types/dispatch'

const props = defineProps<{
  plan: RunPlanResponse | null
  runMode: string
  targetHeadwaySeconds: number
  selectedTrainId?: string
}>()

const emit = defineEmits<{
  selectTrain: [trainId: string]
}>()

function label(type: string) {
  const map: Record<string, string> = {
    PEAK: '高峰',
    FLAT: '平峰',
    OFF_PEAK: '低谷'
  }
  return map[type] ?? type
}

const selectedService = computed(() =>
  props.plan?.services?.find((service) => service.trainId === props.selectedTrainId) ?? null
)

const visibleServices = computed(() => {
  const services = props.plan?.services ?? []
  if (!props.selectedTrainId) return services
  const selected = services.find((service) => service.trainId === props.selectedTrainId)
  return selected ? [selected, ...services.filter((service) => service.trainId !== props.selectedTrainId)] : services
})

function handleTrainSelect(event: Event) {
  emit('selectTrain', (event.target as HTMLSelectElement).value)
}
</script>

<template>
  <section class="panel">
    <header>
      <h2>运行计划</h2>
      <span class="badge">{{ label(runMode) }}</span>
    </header>
    <p class="summary">当前目标发车间隔：<strong>{{ targetHeadwaySeconds }}s</strong></p>
    <p v-if="plan" class="summary">
      正式计划：<strong>{{ plan.services?.length ?? 0 }}</strong> 个车次，
      <strong>{{ plan.circulations?.length ?? 0 }}</strong> 个交路
    </p>
    <div v-if="plan?.services?.length" class="plan-train-picker">
      <label>
        <span>计划车辆</span>
        <select :value="selectedTrainId ?? ''" aria-label="选择计划车辆" @change="handleTrainSelect">
          <option value="">全部车次</option>
          <option v-for="service in plan.services" :key="service.serviceId" :value="service.trainId">
            {{ service.trainId }} · {{ service.serviceId }} · {{ service.circulationId }}
          </option>
        </select>
      </label>
      <p>
        当前 {{ selectedService?.trainId ?? '全部' }}
        <span v-if="selectedService">
          / {{ selectedService.serviceId }} / {{ selectedService.stops[0]?.stationId ?? '-' }} → {{ selectedService.stops.at(-1)?.stationId ?? '-' }}
        </span>
      </p>
    </div>
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
    <div v-if="plan?.services?.length" class="service-list">
      <h3>车次与交路</h3>
      <article
        v-for="service in visibleServices"
        :key="service.serviceId"
        :class="{ selected: service.trainId === selectedTrainId }"
      >
        <strong>{{ service.serviceId }}</strong>
        <span>{{ service.trainId }} / {{ service.circulationId }}</span>
        <span>{{ service.stops[0]?.stationId ?? '-' }} → {{ service.stops.at(-1)?.stationId ?? '-' }}</span>
        <small>计划发车偏移 {{ service.stops[0]?.departureOffsetSec ?? 0 }}s</small>
      </article>
    </div>
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
  align-items: center;
  justify-content: space-between;
  margin-bottom: 8px;
}

h2 {
  margin: 0;
  font-size: 16px;
}

.badge {
  background: var(--status-info-bg);
  color: var(--status-info);
  padding: 4px 10px;
  border-radius: 999px;
  font-size: 12px;
}

.summary {
  margin: 0 0 12px;
  color: var(--text-secondary);
}

.plan-train-picker {
  display: grid;
  gap: 8px;
  margin: 0 0 12px;
  border: 1px solid var(--status-info-bg);
  border-radius: 8px;
  background: var(--bg-inset);
  padding: 10px;
}

.plan-train-picker label {
  display: grid;
  gap: 4px;
}

.plan-train-picker span {
  color: var(--text-secondary);
  font-size: 12px;
  font-weight: 700;
}

.plan-train-picker select {
  min-height: 34px;
  border: 1px solid var(--border-strong);
  border-radius: 8px;
  padding: 6px 10px;
  font: inherit;
}

.plan-train-picker p {
  margin: 0;
  color: var(--status-info);
  font-size: 12px;
}

table {
  width: 100%;
  border-collapse: collapse;
  font-size: 13px;
}

th,
td {
  border-bottom: 1px solid var(--bg-hover);
  padding: 8px 6px;
  text-align: left;
}

.service-list {
  display: grid;
  gap: 8px;
  margin-top: 14px;
}

.service-list h3 {
  margin: 0;
  font-size: 14px;
}

.service-list article {
  display: grid;
  grid-template-columns: 0.8fr 1fr 1fr auto;
  gap: 8px;
  padding: 8px;
  border-radius: 8px;
  background: var(--bg-panel-raised);
  font-size: 12px;
}

.service-list article.selected {
  border: 1px solid var(--accent);
  background: var(--status-info-bg);
}

.service-list small {
  color: var(--text-secondary);
}
</style>
