<script setup lang="ts">
import type { DispatchRouteDecision, DispatchRouteInfo, DispatchRouteReservation } from '../../types/dispatch'
import type { MovementAuthority, RouteState, SignalState, SwitchState, TrainState } from '../../types/simulation'

const props = defineProps<{
  authorities: MovementAuthority[]
  dispatchRouteList: DispatchRouteInfo[]
  selectedRouteId: string
  selectedRouteTrainId: string
  trains: TrainState[]
  routeRequestPending: boolean
  routeOperationMessage: string
  routeDecisions: DispatchRouteDecision[]
  routeReservationsByDecision: Map<string, DispatchRouteReservation>
  routes: RouteState[]
  switches: SwitchState[]
  signals: SignalState[]
  formatNumber: (value: number | null | undefined, digits?: number) => string
  routeStatusLabel: (routeStatus: string) => string
  signalAspectLabel: (aspect: string) => string
}>()

const emit = defineEmits<{
  'update:selectedRouteId': [value: string]
  'update:selectedRouteTrainId': [value: string]
  requestRoute: []
  refreshRoutes: []
}>()
</script>

<template>
  <section class="signal-overview-grid">
    <section class="debug-panel signal-overview-card ma-overview-card">
      <div class="panel-title">
        <h2>信号 MA</h2>
        <span>调度到车辆的中间约束</span>
      </div>
      <div class="overview-list ma-list">
        <article v-for="authority in props.authorities" :key="authority.trainId" class="overview-item ma-item">
          <div class="item-heading">
            <strong>{{ authority.trainId }}</strong>
            <span>{{ props.formatNumber(authority.speedLimitMetersPerSecond) }} m/s</span>
          </div>
          <small>{{ authority.currentSegmentId }} / 到 {{ props.formatNumber(authority.authorityEndMeters) }}m</small>
          <p>{{ authority.reason }}</p>
        </article>
        <p v-if="props.authorities.length === 0" class="empty overview-empty">暂无 MA 数据。</p>
      </div>
    </section>

    <section class="debug-panel signal-overview-card route-overview-card">
      <div class="panel-title">
        <h2>进路</h2>
        <span>道岔调度相关状态</span>
      </div>
      <div class="route-control-bar">
        <select :value="props.selectedRouteId" aria-label="选择进路" @change="emit('update:selectedRouteId', ($event.target as HTMLSelectElement).value)">
          <option value="">选择进路</option>
          <option v-for="route in props.dispatchRouteList" :key="route.routeId" :value="route.routeId">
            {{ route.routeId }} · {{ route.name }} · {{ route.status }}
          </option>
        </select>
        <select :value="props.selectedRouteTrainId" aria-label="选择列车" @change="emit('update:selectedRouteTrainId', ($event.target as HTMLSelectElement).value)">
          <option value="">默认首列车</option>
          <option v-for="train in props.trains" :key="train.id" :value="train.id">{{ train.id }}</option>
        </select>
        <button
          type="button"
          :disabled="!props.selectedRouteId || props.trains.length === 0 || props.routeRequestPending"
          @click="emit('requestRoute')"
        >
          {{ props.routeRequestPending ? '提交中' : '申请进路' }}
        </button>
        <button type="button" class="ghost-button" @click="emit('refreshRoutes')">刷新</button>
      </div>
      <p v-if="props.routeOperationMessage" class="route-operation-message">{{ props.routeOperationMessage }}</p>
      <div v-if="props.routeDecisions.length > 0" class="route-trace-list overview-list">
        <article v-for="decision in props.routeDecisions" :key="decision.decisionId" class="overview-item">
          <div class="item-heading">
            <strong>决策 {{ decision.decisionId }}</strong>
            <span>{{ decision.status }}</span>
          </div>
          <small>指令 {{ decision.routeCommandId }} / 进路 {{ decision.selectedRouteId }}</small>
          <p>
            预留 {{ props.routeReservationsByDecision.get(decision.decisionId)?.reservationId || '-' }}
            / {{ props.routeReservationsByDecision.get(decision.decisionId)?.state || '等待处理' }}
          </p>
        </article>
      </div>
      <div class="overview-list route-list">
        <article v-for="route in props.routes" :key="route.routeId" class="overview-item">
          <div class="item-heading">
            <strong>{{ route.routeId }}</strong>
            <span>{{ props.routeStatusLabel(route.status) }}</span>
          </div>
          <small>列车 {{ route.establishedByTrainId || '-' }}</small>
          <p>锁闭道岔 {{ route.lockedSwitchIds.length }} 个 / 轴占区段 {{ route.axleSegmentIds.length }} 个</p>
        </article>
        <article v-for="route in props.dispatchRouteList" :key="`list-${route.routeId}`" class="overview-item">
          <div class="item-heading">
            <strong>{{ route.routeId }}</strong>
            <span>{{ props.routeStatusLabel(route.status) }}</span>
          </div>
          <small>{{ route.fromStation }} -> {{ route.toStation }}</small>
          <p>{{ route.type }} / {{ route.segmentIds.length }} 个区段</p>
        </article>
        <p v-if="props.routes.length === 0 && props.dispatchRouteList.length === 0" class="empty overview-empty">暂无进路数据。</p>
      </div>
    </section>

    <section class="debug-panel signal-overview-card switch-signal-card">
      <div class="panel-title">
        <h2>道岔与信号机</h2>
        <span>联锁输出</span>
      </div>
      <div class="switch-signal-grid">
        <div class="interlock-column">
          <h3>道岔</h3>
          <article v-for="item in props.switches.slice(0, 8)" :key="item.id" class="overview-item">
            <div class="item-heading">
              <strong>{{ item.id }}</strong>
              <span>{{ item.position === 'NORMAL' ? '定位' : '反位' }}</span>
            </div>
            <small>{{ item.locked ? '锁闭' : '未锁闭' }} / {{ item.activeSegmentId }}</small>
          </article>
          <p v-if="props.switches.length === 0" class="empty overview-empty">暂无道岔数据。</p>
        </div>
        <div class="interlock-column">
          <h3>信号机</h3>
          <article v-for="signal in props.signals.slice(0, 8)" :key="signal.signalId" class="overview-item">
            <div class="item-heading">
              <strong>{{ signal.signalId }}</strong>
              <span :data-aspect="signal.aspect">{{ props.signalAspectLabel(signal.aspect) }}</span>
            </div>
            <small>{{ signal.segmentId }} / {{ signal.reasonTrainId || '-' }}</small>
          </article>
          <p v-if="props.signals.length === 0" class="empty overview-empty">暂无信号数据。</p>
        </div>
      </div>
    </section>
  </section>
</template>

<style scoped>
.signal-overview-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(360px, 1fr));
  align-items: stretch;
  gap: clamp(14px, 1.4vw, 22px);
  max-width: 1500px;
  margin: 0 auto 12px;
}

.debug-panel {
  border: 1px solid var(--border);
  border-radius: 8px;
  background: var(--bg-panel);
  box-shadow: 0 8px 24px rgba(15, 23, 42, 0.05);
}

.signal-overview-card {
  display: flex;
  min-height: clamp(320px, 36vh, 520px);
  flex-direction: column;
  padding: clamp(18px, 1.5vw, 24px);
}

.panel-title {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 12px;
}

.panel-title h2 {
  font-size: clamp(18px, 1.25vw, 22px);
}

.panel-title span,
.empty,
small {
  color: var(--text-secondary);
}

.overview-list,
.switch-signal-grid {
  display: grid;
  gap: 10px;
}

.ma-list,
.route-list,
.switch-signal-grid {
  flex: 1;
}

.overview-item {
  display: grid;
  gap: 6px;
  min-width: 0;
  border: 1px solid var(--border);
  border-radius: 11px;
  background: var(--bg-panel-raised);
  padding: 12px;
}

.ma-item {
  border-color: var(--status-info-bg);
  background: linear-gradient(180deg, var(--bg-inset) 0%, var(--bg-panel) 100%);
}

.item-heading {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  align-items: center;
  gap: 8px;
}

.item-heading strong {
  overflow-wrap: anywhere;
  color: var(--text-primary);
  font-size: 15px;
}

.item-heading span,
[data-aspect] {
  width: fit-content;
  border-radius: 999px;
  background: var(--border);
  color: var(--text-primary);
  padding: 3px 8px;
  font-size: 12px;
  font-weight: 700;
}

.overview-item p {
  margin: 0;
  color: var(--text-secondary);
  font-size: 13px;
  line-height: 1.5;
}

.route-control-bar {
  display: grid;
  grid-template-columns: minmax(180px, 1.5fr) minmax(130px, 1fr);
  gap: 10px;
  margin-bottom: 12px;
}

.route-control-bar select {
  min-height: 38px;
  border: 1px solid var(--border-strong);
  border-radius: 8px;
  padding: 6px 10px;
  font: inherit;
}

.route-control-bar button {
  min-height: 38px;
}

button {
  border: 1px solid var(--accent);
  border-radius: 8px;
  background: var(--accent);
  color: #ffffff;
  padding: 7px 12px;
  font: inherit;
  font-weight: 700;
  cursor: pointer;
}

button:disabled {
  cursor: not-allowed;
  opacity: 0.55;
}

.ghost-button {
  border-color: var(--accent);
  background: var(--bg-panel);
  color: var(--status-info);
}

.route-operation-message {
  margin: 0 0 10px;
  color: var(--status-info);
  font-size: 13px;
}

.route-trace-list {
  margin-bottom: 10px;
}

.switch-signal-grid {
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 14px;
}

.interlock-column {
  display: grid;
  align-content: start;
  gap: 10px;
  min-width: 0;
}

.interlock-column h3 {
  margin: 0;
  color: var(--text-primary);
  font-size: 15px;
}

.overview-empty {
  margin: 0;
  border: 1px dashed var(--border-strong);
  border-radius: 10px;
  background: var(--bg-panel-raised);
  padding: 12px;
  font-size: 13px;
}

[data-aspect='RED'] {
  background: var(--status-danger-bg);
  color: var(--status-danger);
}

[data-aspect='YELLOW'] {
  background: var(--status-warn-bg);
  color: var(--status-warn);
}

[data-aspect='GREEN'] {
  background: var(--status-ok-bg);
  color: var(--status-ok);
}

@media (max-width: 1400px) {
  .signal-overview-grid {
    grid-template-columns: repeat(auto-fit, minmax(320px, 1fr));
  }
}

@media (max-width: 1100px) {
  .signal-overview-grid {
    grid-template-columns: repeat(auto-fit, minmax(300px, 1fr));
  }

  .route-control-bar,
  .switch-signal-grid {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 720px) {
  .signal-overview-grid {
    grid-template-columns: 1fr;
  }

  .signal-overview-card {
    min-height: auto;
  }
}
</style>
