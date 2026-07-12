<script setup lang="ts">
import type { DispatchRouteDecision, DispatchRouteReservation } from '../../types/dispatch'

defineProps<{
  decisions: DispatchRouteDecision[]
  reservations: DispatchRouteReservation[]
}>()
</script>

<template>
  <section class="panel">
    <header>
      <h2>进路调度状态闭环</h2>
      <span>仲裁、联锁反馈、重试、取消与超时</span>
    </header>
    <p v-if="reservations.length === 0" class="empty">暂无进路预约记录</p>
    <div v-else class="list">
      <article v-for="item in reservations" :key="item.reservationId">
        <div class="title">
          <strong>{{ item.trainId }} → {{ item.routeId }}</strong>
          <b :data-state="item.state">{{ item.state }}</b>
        </div>
        <div class="meta">
          <span>重试 {{ item.retryCount }}</span>
          <span>{{ item.retryable ? '允许重试' : '不可重试/无需重试' }}</span>
          <span v-if="item.failureCode">{{ item.failureCode }} / {{ item.failureCategory }}</span>
          <span v-if="item.nextRetryAt">下次可重试 {{ new Date(item.nextRetryAt).toLocaleTimeString() }}</span>
          <span v-if="item.cancelCommandId">取消指令 {{ item.cancelCommandId }}</span>
        </div>
        <p v-if="item.rejectReason">{{ item.rejectReason }}</p>
      </article>
    </div>
    <div v-if="decisions.length" class="waiting">
      <span v-for="decision in decisions" :key="decision.decisionId">
        {{ decision.selectedTrainId }} 等待 {{ decision.waitingSeconds.toFixed(0) }}s，
        等待列车 {{ decision.waitingTrainIds.join(', ') || '-' }}
      </span>
    </div>
  </section>
</template>

<style scoped>
.panel { background:#fff; border:1px solid #e2e8f0; border-radius:12px; padding:16px; }
header,.title { display:flex; justify-content:space-between; gap:12px; align-items:baseline; }
h2 { margin:0; font-size:16px; }
header span,.empty,.meta,.waiting { color:#64748b; font-size:12px; }
.list { display:grid; gap:8px; margin-top:12px; }
article { padding:10px; border:1px solid #e2e8f0; border-radius:8px; }
.meta { display:flex; flex-wrap:wrap; gap:6px 12px; margin-top:6px; }
article p { margin:6px 0 0; color:#b91c1c; font-size:12px; }
b[data-state='ACCEPTED'] { color:#059669; }
b[data-state='REJECTED'],b[data-state='TIMEOUT'] { color:#dc2626; }
b[data-state='REQUESTED'] { color:#2563eb; }
.waiting { display:grid; gap:4px; margin-top:10px; }
</style>
