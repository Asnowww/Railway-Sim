<script setup lang="ts">
import type { DispatchCommandView } from '../../types/dispatch'

defineProps<{
  commands: DispatchCommandView[]
}>()
</script>

<template>
  <section class="panel">
    <h2>调度指令</h2>
    <p v-if="commands.length === 0" class="empty">暂无活跃指令</p>
    <ul v-else>
      <li v-for="item in commands" :key="item.id">
        <div class="row">
          <strong>{{ item.commandType }}</strong>
          <span class="status" :data-status="item.status">{{ item.status }}</span>
        </div>
        <div class="meta">列车 {{ item.trainId }} · 原因 {{ item.reason }}</div>
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
  border-left: 3px solid #3b82f6;
  padding: 8px 10px;
  background: #f8fafc;
  border-radius: 0 8px 8px 0;
}

.row {
  display: flex;
  justify-content: space-between;
  gap: 8px;
}

.meta {
  margin-top: 4px;
  font-size: 12px;
  color: #64748b;
}

.status {
  font-size: 11px;
  padding: 2px 8px;
  border-radius: 999px;
  background: #e2e8f0;
}

.status[data-status='APPLIED'] {
  background: #dcfce7;
  color: #166534;
}

.status[data-status='PENDING'] {
  background: #fef3c7;
  color: #92400e;
}
</style>
