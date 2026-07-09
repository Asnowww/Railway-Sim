<script setup lang="ts">
import { ref } from 'vue'
import DispatchStatusBar from './components/dispatch/DispatchStatusBar.vue'
import VehicleSelfControlDemo from './components/power-train/VehicleSelfControlDemo.vue'
import { useSimulation } from './composables/useSimulation'
import DispatchView from './views/dispatch/DispatchView.vue'

const activeTab = ref<'dispatch' | 'vehicle' | 'home'>('dispatch')

const {
  plan,
  snapshot,
  dispatch,
  status,
  tick,
  errorMessage,
  backendReady,
  autoRunning,
  runSimulation,
  toggleAutoRun
} = useSimulation()
</script>

<template>
  <div class="app">
    <header class="top-bar">
      <div class="brand">
        <strong>Railway-Sim</strong>
        <span class="hint">前端 http://localhost:5173 · 后端 http://localhost:8080</span>
      </div>
      <nav class="tabs">
        <button type="button" :class="{ active: activeTab === 'dispatch' }" @click="activeTab = 'dispatch'">
          运营调度
        </button>
        <button type="button" :class="{ active: activeTab === 'vehicle' }" @click="activeTab = 'vehicle'">
          车辆供电
        </button>
        <button type="button" :class="{ active: activeTab === 'home' }" @click="activeTab = 'home'">
          首页
        </button>
      </nav>
    </header>

    <DispatchStatusBar
      class="global-controls"
      :status="status"
      :run-mode="dispatch.runMode"
      :target-headway-seconds="dispatch.targetHeadwaySeconds"
      :intervention-active="dispatch.interventionActive"
      :tick="tick"
      @start="runSimulation('start')"
      @pause="runSimulation('pause')"
      @reset="runSimulation('reset')"
      @tick="runSimulation('tick')"
    >
      <template #extra-actions>
        <button
          type="button"
          class="auto-btn"
          :class="{ running: autoRunning }"
          @click="toggleAutoRun"
        >
          {{ autoRunning ? '停止自动' : '自动步进' }}
        </button>
      </template>
    </DispatchStatusBar>

    <p v-if="errorMessage" class="banner error">{{ errorMessage }}</p>
    <p v-else-if="!backendReady" class="banner warn">
      正在连接后端… 若长时间无响应，请先启动后端再刷新页面。
    </p>
    <p v-else-if="status === 'STOPPED'" class="banner info">
      后端已连接。请点击上方「启动」，再点「步进」或「自动步进」观察列车与调度数据。
    </p>

    <DispatchView
      v-if="activeTab === 'dispatch'"
      :plan="plan"
      :snapshot="snapshot"
      :dispatch="dispatch"
    />

    <VehicleSelfControlDemo v-else-if="activeTab === 'vehicle'" />

    <main v-else class="app-shell">
      <h1>Railway-Sim</h1>
      <p>上京地铁仿真系统。请切换到「运营调度」页，使用顶部按钮控制仿真。</p>
      <ul>
        <li>启动后端：<code>mvn spring-boot:run</code>（在 backend 目录）</li>
        <li>启动前端：<code>pnpm --filter railway-sim-frontend dev</code></li>
        <li>浏览器打开：<code>http://localhost:5173</code></li>
      </ul>
    </main>
  </div>
</template>

<style scoped>
.app {
  min-height: 100vh;
  padding-bottom: 24px;
}

.top-bar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 12px 20px 0;
  max-width: 1200px;
  margin: 0 auto;
}

.brand {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.hint {
  color: #64748b;
  font-size: 12px;
}

.tabs {
  display: flex;
  gap: 8px;
}

.tabs button {
  border: 1px solid #cbd5e1;
  background: #fff;
  color: #475569;
  border-radius: 8px;
  padding: 10px 16px;
  cursor: pointer;
}

.tabs button.active {
  background: #2563eb;
  border-color: #2563eb;
  color: #fff;
}

.global-controls {
  max-width: 1200px;
  margin: 12px auto 0;
}

.auto-btn {
  border: 1px solid #cbd5e1;
  background: #fff;
  color: #334155;
  border-radius: 8px;
  padding: 10px 16px;
  cursor: pointer;
  font-size: 14px;
  font-weight: 500;
}

.auto-btn.running {
  background: #059669;
  border-color: #059669;
  color: #fff;
}

.banner {
  max-width: 1200px;
  margin: 12px auto 0;
  padding: 10px 14px;
  border-radius: 8px;
  font-size: 13px;
}

.banner.error {
  background: #fef2f2;
  color: #b91c1c;
}

.banner.warn {
  background: #fffbeb;
  color: #92400e;
}

.banner.info {
  background: #eff6ff;
  color: #1e40af;
}

.app-shell {
  max-width: 720px;
  margin: 40px auto;
  padding: 0 20px;
  line-height: 1.6;
}

h1 {
  margin: 0 0 8px;
  font-size: 32px;
}

p {
  margin: 0 0 12px;
  color: #64748b;
}

code {
  background: #f1f5f9;
  padding: 2px 6px;
  border-radius: 4px;
  font-size: 12px;
}
</style>
