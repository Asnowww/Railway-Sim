<script setup lang="ts">
import type { DispatchDisturbance } from '../../types/dispatch'

defineProps<{
  disturbances: DispatchDisturbance[]
}>()
</script>

<template>
  <section class="panel">
    <h2>运行扰动</h2>
    <p v-if="disturbances.length === 0" class="empty">暂无开放扰动</p>
    <ul v-else>
      <li v-for="item in disturbances" :key="item.id">
        <div class="title">{{ item.disturbanceType }}</div>
        <div class="meta">
          列车 {{ item.trainId }}
          <span v-if="item.stationId"> · 站点 {{ item.stationId }}</span>
          · 偏差 {{ item.deviationValue.toFixed(1) }}
        </div>
        <span class="status">{{ item.status }}</span>
      </li>
    </ul>
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
  margin: 0 0 12px;
  font-size: 16px;
}

.empty {
  margin: 0;
  color: #94a3b8;
}

ul {
  list-style: none;
  margin: 0;
  padding: 0;
  display: grid;
  gap: 10px;
}

li {
  border: 1px solid #f1f5f9;
  border-radius: 8px;
  padding: 10px;
}

.title {
  font-weight: 600;
  font-size: 14px;
}

.meta {
  color: #64748b;
  font-size: 12px;
  margin-top: 4px;
}

.status {
  display: inline-block;
  margin-top: 6px;
  font-size: 11px;
  color: #b45309;
  background: #fef3c7;
  padding: 2px 8px;
  border-radius: 999px;
}
</style>
