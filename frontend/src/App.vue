<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { routes } from './app/router'
import { useConnectionStore } from './stores/connection'
import { useSimulationStore } from './stores/simulation'
import { useTopologyStore } from './stores/topology'
import { simulationStatusLabel, healthTone, serviceHealthLabel } from './shared/labels'
import type { Tone } from './shared/labels'
import StatusBadge from './shared/components/StatusBadge.vue'
import ToastHost from './shared/components/ToastHost.vue'
import ConfirmDialogHost from './shared/components/ConfirmDialogHost.vue'

const route = useRoute()
const connection = useConnectionStore()
const simulation = useSimulationStore()
const topology = useTopologyStore()

onMounted(() => {
  connection.start()
  void topology.load()
})

onBeforeUnmount(() => {
  connection.stop()
})

const navItems = computed(() =>
  routes
    .filter((item) => typeof item.meta?.nav === 'string' && !item.meta?.debug)
    .map((item) => ({ path: item.path, label: item.meta!.nav as string, icon: (item.meta!.icon as string) ?? '', simulated: Boolean(item.meta!.simulated) }))
)

const debugNavItems = computed(() =>
  routes
    .filter((item) => item.meta?.debug)
    .map((item) => ({ path: item.path, label: item.meta!.nav as string }))
)

const linkBadge = computed<{ tone: Tone; label: string; blink: boolean }>(() => {
  switch (connection.linkQuality) {
    case 'up':
      if (connection.idleQuiet) return { tone: 'info', label: '已连接 · 仿真未运行', blink: false }
      return { tone: 'ok', label: '实时数据', blink: false }
    case 'stale':
      return { tone: 'stale', label: `数据过期 · 最后 ${connection.lastSnapshotClock || '—'}`, blink: false }
    case 'connecting':
      return { tone: 'info', label: '连接中…', blink: false }
    default:
      return { tone: 'danger', label: '后端离线', blink: true }
  }
})

const statusTone = computed<Tone>(() => {
  switch (simulation.status) {
    case 'RUNNING':
      return 'ok'
    case 'PAUSED':
      return 'warn'
    default:
      return 'neutral'
  }
})

const runtimeBadge = computed<{ tone: Tone; label: string } | null>(() => {
  const health = simulation.vehicleRuntime
  if (!health) return null
  return {
    tone: healthTone(health.heartbeatStatus),
    label: `车辆运行时 ${serviceHealthLabel(health.heartbeatStatus)}`
  }
})

const isDebugRoute = computed(() => Boolean(route.meta.debug))
</script>

<template>
  <div class="shell">
    <header class="topbar">
      <div class="brand">
        <span class="brand-mark" aria-hidden="true">RS</span>
        <div>
          <h1>Railway-Sim 综合监控</h1>
          <span class="run-id mono" :title="'仿真运行 ID'">{{ simulation.simulationRunId ?? '未连接运行' }}</span>
        </div>
      </div>

      <div class="topbar-status" role="status" aria-label="系统状态">
        <StatusBadge :tone="statusTone" :label="simulationStatusLabel(simulation.status)" />
        <span class="clock-block">
          <span class="clock-label">仿真时间</span>
          <strong class="num">{{ simulation.simulatedClock }}</strong>
        </span>
        <span class="clock-block">
          <span class="clock-label">Tick</span>
          <strong class="num">{{ simulation.tick }}</strong>
        </span>
        <StatusBadge :tone="linkBadge.tone" :label="linkBadge.label" :blink="linkBadge.blink" />
        <StatusBadge v-if="runtimeBadge" :tone="runtimeBadge.tone" :label="runtimeBadge.label" />
        <RouterLink to="/ops" class="alarm-link" :class="{ active: simulation.unconfirmedAlarmCount > 0 }">
          <span aria-hidden="true">⚠</span>
          未确认告警 <strong class="num">{{ simulation.unconfirmedAlarmCount }}</strong>
        </RouterLink>
      </div>
    </header>

    <div class="body">
      <nav class="sidenav" aria-label="工作台导航">
        <RouterLink v-for="item in navItems" :key="item.path" :to="item.path" class="nav-item" active-class="active">
          <span class="nav-icon" aria-hidden="true">{{ item.icon }}</span>
          <span>{{ item.label }}</span>
          <span v-if="item.simulated" class="sim-tag" title="该工作台使用模拟数据">模拟</span>
        </RouterLink>

        <div class="nav-divider" role="separator">
          <span>联调工具</span>
        </div>
        <RouterLink v-for="item in debugNavItems" :key="item.path" :to="item.path" class="nav-item debug" active-class="active">
          <span class="nav-icon" aria-hidden="true">⚙</span>
          <span>{{ item.label }}</span>
        </RouterLink>
      </nav>

      <main class="content" :class="{ 'debug-frame': isDebugRoute }">
        <div v-if="isDebugRoute" class="debug-banner" role="note">
          联调工具页 —— 非生产监控界面，允许直连调试接口与本地演示模型
        </div>
        <RouterView />
      </main>
    </div>

    <ToastHost />
    <ConfirmDialogHost />
  </div>
</template>

<style scoped>
.shell {
  height: 100vh;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.topbar {
  flex: none;
  height: var(--topbar-height);
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--gap-lg);
  padding: 0 var(--gap-lg);
  background: var(--bg-shell);
  border-bottom: 1px solid var(--border);
}

.brand {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
}

.brand-mark {
  width: 30px;
  height: 30px;
  display: grid;
  place-items: center;
  border-radius: var(--radius-md);
  background: var(--accent);
  color: #fff;
  font-weight: 800;
  font-size: var(--fs-sm);
  flex: none;
}

.brand h1 {
  font-size: var(--fs-md);
  line-height: 1.1;
  white-space: nowrap;
}

.run-id {
  font-size: 10px;
  color: var(--text-muted);
}

.topbar-status {
  display: flex;
  align-items: center;
  gap: var(--gap-md);
  flex-wrap: nowrap;
}

.clock-block {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  line-height: 1.2;
}

.clock-label {
  font-size: 10px;
  color: var(--text-muted);
}

.clock-block strong {
  font-size: var(--fs-sm);
}

.alarm-link {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 3px 10px;
  border-radius: 999px;
  border: 1px solid var(--border-strong);
  color: var(--text-secondary);
  font-size: var(--fs-xs);
}

.alarm-link.active {
  border-color: var(--status-danger);
  color: var(--status-danger);
  background: var(--status-danger-bg);
}

.body {
  flex: 1;
  display: flex;
  min-height: 0;
}

.sidenav {
  flex: none;
  width: var(--nav-width);
  background: var(--bg-shell);
  border-right: 1px solid var(--border);
  padding: var(--gap-md) var(--gap-sm);
  display: flex;
  flex-direction: column;
  gap: 2px;
  overflow-y: auto;
}

.nav-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 10px;
  border-radius: var(--radius-md);
  color: var(--text-secondary);
  font-size: var(--fs-sm);
  font-weight: 600;
}

.nav-item:hover {
  background: var(--bg-hover);
  color: var(--text-primary);
}

.nav-item.active {
  background: var(--accent-muted);
  color: var(--accent);
}

.nav-icon {
  width: 16px;
  text-align: center;
  flex: none;
}

.sim-tag {
  margin-left: auto;
  font-size: 10px;
  padding: 0 6px;
  border-radius: 999px;
  background: var(--status-warn-bg);
  color: var(--status-warn);
  border: 1px solid var(--status-warn);
  font-weight: 700;
}

.nav-divider {
  margin: var(--gap-md) 4px 4px;
  border-top: 1px solid var(--border);
  padding-top: 8px;
  font-size: 10px;
  color: var(--text-muted);
  letter-spacing: 0.1em;
}

.nav-item.debug {
  color: var(--text-muted);
}

.nav-item.debug.active {
  background: var(--status-warn-bg);
  color: var(--status-warn);
}

.content {
  flex: 1;
  min-width: 0;
  min-height: 0;
  overflow: auto;
  padding: var(--gap-lg);
  display: flex;
  flex-direction: column;
  gap: var(--gap-lg);
}

.content.debug-frame {
  padding: 0;
  gap: 0;
}

.debug-banner {
  flex: none;
  background: var(--status-warn-bg);
  color: var(--status-warn);
  border-bottom: 1px solid var(--status-warn);
  padding: 6px var(--gap-lg);
  font-size: var(--fs-xs);
  font-weight: 700;
}
</style>
