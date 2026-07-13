<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import { storeToRefs } from 'pinia'
import { useSimulationStore } from '../../stores/simulation'
import { useConnectionStore } from '../../stores/connection'
import { useTopologyStore } from '../../stores/topology'
import { signalTrackApi, type RouteLifecycleEvent, type SignalRouteInfo } from '../../api/signalTrack'
import { newTraceId } from '../../shared/api/client'
import Panel from '../../shared/components/Panel.vue'
import StatusBadge from '../../shared/components/StatusBadge.vue'
import LineDiagram, { type DiagramLayers } from '../overview/LineDiagram.vue'
import { requestConfirm } from '../../shared/confirm'
import { toastError, toastOk } from '../../shared/toast'
import {
  occupancyLabel,
  occupancyTone,
  signalAspectLabel,
  signalTone,
  switchPositionLabel,
  routeStatusLabel,
  routeStatusTone
} from '../../shared/labels'

const route = useRoute()
const simulation = useSimulationStore()
const connection = useConnectionStore()
const topology = useTopologyStore()
const { trackSegments, signalStates, switchStates, routeStates, authorities, trains, status } = storeToRefs(simulation)

const layers = reactive<DiagramLayers>({ track: true, trains: true, signals: true, switches: true, ma: true, power: false })

const selectedSegmentId = ref<string>((route.query.segment as string) ?? '')
const selectedRouteId = ref('')

const highlightSegments = computed(() => {
  if (!selectedRouteId.value) return null
  const routeState = routeStates.value.find((item) => item.routeId === selectedRouteId.value)
  return routeState ? routeState.axleSegmentIds : null
})

const selectedSegment = computed(() => trackSegments.value.find((segment) => segment.id === selectedSegmentId.value) ?? null)

/* ---------- 进路静态信息 + 事件流 ---------- */

const routeInfos = ref<SignalRouteInfo[]>([])
const routeEvents = ref<Array<RouteLifecycleEvent & { receivedAt: string }>>([])
let eventTimer = 0

async function pollRouteEvents(): Promise<void> {
  try {
    const events = await signalTrackApi.routeEvents()
    if (events.length > 0) {
      const stamp = new Date().toLocaleTimeString('zh-CN', { hour12: false })
      routeEvents.value.unshift(...events.map((event) => ({ ...event, receivedAt: stamp })))
      routeEvents.value = routeEvents.value.slice(0, 50)
    }
  } catch {
    /* 事件流轮询失败静默——连接状态已由全局状态条呈现 */
  }
}

onMounted(() => {
  void signalTrackApi
    .routes()
    .then((routes) => (routeInfos.value = routes))
    .catch(() => undefined)
  void refreshFaults()
  void pollRouteEvents()
  eventTimer = window.setInterval(() => void pollRouteEvents(), 5000)
})

onBeforeUnmount(() => window.clearInterval(eventTimer))

const routeInfoById = computed(() => new Map(routeInfos.value.map((info) => [info.routeId, info])))

/* ---------- 信号轨道故障注入 ---------- */

const FAULT_TYPES = [
  { value: 'TRACK_CIRCUIT_OCCUPIED', label: '轨道电路误占用' },
  { value: 'TRACK_CIRCUIT_UNKNOWN', label: '轨道电路状态未知' },
  { value: 'SWITCH_NO_INDICATION', label: '道岔无表示' },
  { value: 'SWITCH_OUT_OF_CONTROL', label: '道岔失控' },
  { value: 'SIGNAL_LAMP_FAILURE', label: '信号灯丝断丝' },
  { value: 'SIGNAL_COMM_LOSS', label: '信号通信中断' },
  { value: 'TRAIN_POSITION_INVALID', label: '列车位置无效' }
]

const faultForm = ref({ sourceId: '', faultType: FAULT_TYPES[1]!.value })
const activeFaults = ref<string[]>([])
const faultBusy = ref(false)

async function refreshFaults(): Promise<void> {
  try {
    activeFaults.value = await signalTrackApi.faults()
  } catch {
    /* 列表刷新失败不打断操作流 */
  }
}

async function injectFault(): Promise<void> {
  if (!faultForm.value.sourceId || faultBusy.value) return
  const meta = FAULT_TYPES.find((item) => item.value === faultForm.value.faultType)
  const confirm = await requestConfirm({
    title: '注入信号/轨道故障',
    lines: [`目标：${faultForm.value.sourceId}`, `故障类型：${meta?.label ?? faultForm.value.faultType}`],
    danger: true,
    requireReason: true
  })
  if (!confirm.ok) return
  faultBusy.value = true
  const traceId = newTraceId('sigfault')
  try {
    const result = await signalTrackApi.injectFault({
      sourceId: faultForm.value.sourceId,
      faultType: faultForm.value.faultType,
      operator: confirm.operator,
      reason: confirm.reason,
      traceId
    })
    if (result.idempotent) toastOk('故障已存在（幂等）', `${result.sourceId}`, traceId)
    else toastOk('故障注入成功', `${result.sourceId} · ${meta?.label ?? result.faultType}`, traceId)
    await refreshFaults()
  } catch (error) {
    toastError('故障注入失败', error)
  } finally {
    faultBusy.value = false
  }
}

async function clearFault(segmentId: string): Promise<void> {
  const confirm = await requestConfirm({
    title: '清除区段故障',
    lines: [`目标区段：${segmentId}`],
    requireReason: true
  })
  if (!confirm.ok) return
  faultBusy.value = true
  const traceId = newTraceId('sigclear')
  try {
    await signalTrackApi.clearFault(segmentId, confirm.operator, confirm.reason, traceId)
    toastOk('故障已清除', segmentId, traceId)
    await refreshFaults()
  } catch (error) {
    toastError('清除失败', error)
  } finally {
    faultBusy.value = false
  }
}

const stale = computed(() => !connection.dataTrusted)
</script>

<template>
  <div class="signal-track">
    <Panel
      title="轨道与信号态势"
      :subtitle="`${trackSegments.length} 区段 · ${signalStates.length} 信号机 · ${switchStates.length} 道岔 · ${routeStates.length} 进路`"
      :stale="stale"
      :stale-since="connection.lastSnapshotClock"
    >
      <template #actions>
        <select v-model="selectedRouteId" aria-label="进路高亮">
          <option value="">无进路高亮</option>
          <option v-for="routeState in routeStates" :key="routeState.routeId" :value="routeState.routeId">
            {{ routeState.routeId }} · {{ routeStatusLabel(routeState.status) }}
          </option>
        </select>
      </template>
      <LineDiagram
        :segments="trackSegments.length > 0 ? trackSegments : topology.topology?.segments ?? []"
        :stations="topology.stations"
        :trains="trains"
        :authorities="authorities"
        :signals="signalStates"
        :switches="switchStates"
        :layers="layers"
        :highlight-segment-ids="highlightSegments"
        :selected-segment-id="selectedSegmentId"
        :height="300"
        @select-segment="(id) => (selectedSegmentId = id)"
      />
    </Panel>

    <div class="tables-row">
      <Panel title="轨道区段" flush>
        <div class="scroll-y table-h">
          <table class="data">
            <thead>
              <tr>
                <th>区段</th>
                <th>范围</th>
                <th>占用</th>
                <th>限速</th>
                <th>节点</th>
                <th>股道</th>
              </tr>
            </thead>
            <tbody>
              <tr
                v-for="segment in trackSegments"
                :key="segment.id"
                :class="['clickable', { selected: segment.id === selectedSegmentId }]"
                @click="selectedSegmentId = segment.id"
              >
                <td class="mono">{{ segment.id }}</td>
                <td class="num">{{ Math.round(segment.startMeters) }}–{{ Math.round(segment.endMeters) }}m</td>
                <td><StatusBadge :tone="occupancyTone(segment.occupancy)" :label="occupancyLabel(segment.occupancy)" /></td>
                <td class="num">{{ Math.round(segment.speedLimitMetersPerSecond * 3.6) }} km/h</td>
                <td class="mono small">{{ segment.fromNode }}→{{ segment.toNode }}</td>
                <td class="mono small">{{ segment.track }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </Panel>

      <Panel title="信号机" flush>
        <div class="scroll-y table-h">
          <table class="data">
            <thead>
              <tr>
                <th>信号机</th>
                <th>区段</th>
                <th>位置</th>
                <th>显示</th>
                <th>原因车</th>
              </tr>
            </thead>
            <tbody>
              <tr v-if="signalStates.length === 0">
                <td colspan="5" class="empty">暂无信号机数据</td>
              </tr>
              <tr v-for="signal in signalStates" :key="signal.signalId">
                <td class="mono">{{ signal.signalId }}</td>
                <td class="mono">{{ signal.segmentId }}</td>
                <td class="num">{{ Math.round(signal.positionMeters) }}m</td>
                <td>
                  <StatusBadge
                    :tone="signalTone(signal.aspect)"
                    :label="signalAspectLabel(signal.aspect)"
                    :shape="signal.aspect === 'GREEN' ? 'dot' : signal.aspect === 'YELLOW' ? 'triangle' : 'square'"
                  />
                </td>
                <td class="mono">{{ signal.reasonTrainId || '—' }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </Panel>

      <Panel title="道岔" flush>
        <div class="scroll-y table-h">
          <table class="data">
            <thead>
              <tr>
                <th>道岔</th>
                <th>节点</th>
                <th>位置</th>
                <th>锁闭</th>
                <th>开通区段</th>
              </tr>
            </thead>
            <tbody>
              <tr v-if="switchStates.length === 0">
                <td colspan="5" class="empty">当前线路无道岔（直线线路）</td>
              </tr>
              <tr v-for="switchState in switchStates" :key="switchState.id">
                <td class="mono">{{ switchState.id }}</td>
                <td class="mono">{{ switchState.nodeId }}</td>
                <td>
                  <StatusBadge
                    :tone="switchState.position === 'REVERSE' ? 'warn' : 'ok'"
                    :label="switchPositionLabel(switchState.position)"
                  />
                </td>
                <td>{{ switchState.locked ? '已锁闭' : '未锁闭' }}</td>
                <td class="mono">{{ switchState.activeSegmentId }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </Panel>
    </div>

    <!-- 进路独占一整行 -->
    <div class="route-row">
      <Panel title="进路" flush>
        <div class="scroll-y route-table">
          <table class="data">
            <thead>
              <tr>
                <th>进路</th>
                <th>名称/走向</th>
                <th>状态</th>
                <th>占用车</th>
                <th>锁闭道岔</th>
              </tr>
            </thead>
            <tbody>
              <tr v-if="routeStates.length === 0">
                <td colspan="5" class="empty">暂无进路数据</td>
              </tr>
              <tr
                v-for="routeState in routeStates"
                :key="routeState.routeId"
                class="clickable"
                @click="selectedRouteId = selectedRouteId === routeState.routeId ? '' : routeState.routeId"
              >
                <td class="mono">{{ routeState.routeId }}</td>
                <td class="small">
                  {{ routeInfoById.get(routeState.routeId)?.name || '—' }}
                  <span class="muted">
                    {{ routeInfoById.get(routeState.routeId)?.fromStation }}→{{ routeInfoById.get(routeState.routeId)?.toStation }}
                  </span>
                </td>
                <td><StatusBadge :tone="routeStatusTone(routeState.status)" :label="routeStatusLabel(routeState.status)" /></td>
                <td class="mono">{{ routeState.establishedByTrainId || '—' }}</td>
                <td class="mono small">{{ routeState.lockedSwitchIds.join(', ') || '—' }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </Panel>
    </div>

    <div class="bottom-grid">
      <Panel title="故障注入" subtitle="严格枚举校验；操作写入后端审计日志">
        <div class="fault-form">
          <label class="field">
            <span>目标区段</span>
            <select v-model="faultForm.sourceId">
              <option value="" disabled>选择区段</option>
              <option v-for="segment in trackSegments" :key="segment.id" :value="segment.id">{{ segment.id }}</option>
            </select>
          </label>
          <label class="field">
            <span>故障类型</span>
            <select v-model="faultForm.faultType">
              <option v-for="type in FAULT_TYPES" :key="type.value" :value="type.value">{{ type.label }}</option>
            </select>
          </label>
          <button type="button" class="btn danger" :disabled="!faultForm.sourceId || faultBusy" @click="injectFault">
            注入故障
          </button>
        </div>

        <h4 class="sub-title">当前故障区段（{{ activeFaults.length }}）</h4>
        <div class="active-faults">
          <p v-if="activeFaults.length === 0" class="muted">无活动故障</p>
          <span v-for="segmentId in activeFaults" :key="segmentId" class="fault-chip">
            <StatusBadge tone="fault" :label="segmentId" blink />
            <button type="button" class="btn sm" :disabled="faultBusy" @click="clearFault(segmentId)">清除</button>
          </span>
        </div>
        <p v-if="status === 'RUNNING'" class="muted">提示：`/api/simulation/fault/*` 的简易注入需暂停仿真，本面板的 `/api/signal-track/faults` 不受该限制。</p>
      </Panel>

      <Panel title="进路事件流" subtitle="每 5 秒轮询增量事件" flush>
        <div class="scroll-y table-h">
          <table class="data">
            <thead>
              <tr>
                <th>Tick</th>
                <th>进路</th>
                <th>状态流转</th>
                <th>列车</th>
                <th>说明</th>
              </tr>
            </thead>
            <tbody>
              <tr v-if="routeEvents.length === 0">
                <td colspan="5" class="empty">暂无进路生命周期事件</td>
              </tr>
              <tr v-for="(event, index) in routeEvents" :key="index">
                <td class="num">{{ event.tick ?? '—' }}</td>
                <td class="mono">{{ event.routeId ?? '—' }}</td>
                <td>{{ routeStatusLabel(event.from) }} → {{ routeStatusLabel(event.to) }}</td>
                <td class="mono">{{ event.trainId ?? '—' }}</td>
                <td class="small">{{ event.detail ?? '—' }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </Panel>

      <Panel :title="selectedSegment ? `区段详情 · ${selectedSegment.id}` : '区段详情'">
        <dl v-if="selectedSegment" class="detail-grid">
          <div><dt>占用</dt><dd><StatusBadge :tone="occupancyTone(selectedSegment.occupancy)" :label="occupancyLabel(selectedSegment.occupancy)" /></dd></div>
          <div><dt>范围</dt><dd class="num">{{ Math.round(selectedSegment.startMeters) }} – {{ Math.round(selectedSegment.endMeters) }} m</dd></div>
          <div><dt>限速</dt><dd class="num">{{ Math.round(selectedSegment.speedLimitMetersPerSecond * 3.6) }} km/h</dd></div>
          <div><dt>节点</dt><dd class="mono">{{ selectedSegment.fromNode }} → {{ selectedSegment.toNode }}</dd></div>
          <div><dt>股道</dt><dd class="mono">{{ selectedSegment.track }}</dd></div>
          <div>
            <dt>占用车</dt>
            <dd class="mono">
              {{ authorities.filter((a) => a.currentSegmentId === selectedSegment!.id).map((a) => a.trainId).join(', ') || '—' }}
            </dd>
          </div>
        </dl>
        <p v-else class="muted">点击态势图或轨道区段表中的区段查看详情</p>
      </Panel>
    </div>
  </div>
</template>

<style scoped>
.signal-track {
  display: flex;
  flex-direction: column;
  gap: var(--gap-lg);
}

/* 第一行：轨道区段 | 信号机 | 道岔 —— 三卡同行等高，内部滚动 */
.tables-row {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: var(--gap-lg);
  align-items: stretch;
}

@media (max-width: 1250px) {
  .tables-row {
    grid-template-columns: 1fr;
  }
}

.table-h {
  height: 320px;
  max-height: none;
}

/* 进路独占一整行 */
.route-table {
  max-height: 320px;
}

/* 底部：故障注入 | 进路事件流 | 区段详情 —— 三卡同行等高 */
.bottom-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: var(--gap-lg);
  align-items: stretch;
}

@media (max-width: 1250px) {
  .bottom-grid {
    grid-template-columns: 1fr;
  }
}

/* 事件流表在等高行内吃满剩余高度并内部滚动 */
.bottom-grid .table-h {
  height: auto;
  flex: 1;
  min-height: 0;
  max-height: 300px;
}

.fault-form {
  display: grid;
  grid-template-columns: 1fr 1.4fr auto;
  gap: var(--gap-md);
  align-items: end;
}

.sub-title {
  font-size: var(--fs-sm);
  color: var(--text-secondary);
}

.active-faults {
  display: flex;
  flex-wrap: wrap;
  gap: var(--gap-sm);
  align-items: center;
}

.fault-chip {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}

.detail-grid {
  margin: 0;
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: var(--gap-md);
}

.detail-grid div {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.detail-grid dt {
  font-size: var(--fs-xs);
  color: var(--text-muted);
}

.detail-grid dd {
  margin: 0;
  font-weight: 600;
}

.small { font-size: var(--fs-xs); }
.muted { color: var(--text-muted); font-size: var(--fs-xs); }
.empty { text-align: center; color: var(--text-muted); }
</style>
