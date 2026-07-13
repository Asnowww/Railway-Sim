<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { storeToRefs } from 'pinia'
import { dispatchApi } from '../../api/dispatch'
import { useSimulationStore } from '../../stores/simulation'
import { useConnectionStore } from '../../stores/connection'
import { useTopologyStore } from '../../stores/topology'
import Panel from '../../shared/components/Panel.vue'
import StatusBadge from '../../shared/components/StatusBadge.vue'
import RunPlanPanel from '../../components/dispatch/RunPlanPanel.vue'
import HeadwayChart from '../../components/dispatch/HeadwayChart.vue'
import SelfRegulationPanel from '../../components/dispatch/SelfRegulationPanel.vue'
import StationHeadwayPanel from '../../components/dispatch/StationHeadwayPanel.vue'
import RouteClosurePanel from '../../components/dispatch/RouteClosurePanel.vue'
import DisturbanceList from '../../components/dispatch/DisturbanceList.vue'
import CommandTimeline from '../../components/dispatch/CommandTimeline.vue'
import { toastError, toastOk } from '../../shared/toast'
import {
  dispatchCommandTypeLabel,
  dispatchCommandStatusLabel,
  commandStatusTone,
  runModeLabel,
  stationEventTypeLabel
} from '../../shared/labels'
import type { RunPlanResponse, TrainStationEvent, DispatchCommandView, DispatchRouteInfo } from '../../types/dispatch'

const simulation = useSimulationStore()
const connection = useConnectionStore()
const topology = useTopologyStore()
const { dispatch, trains } = storeToRefs(simulation)

const plan = ref<RunPlanResponse | null>(null)
const routeList = ref<DispatchRouteInfo[]>([])
const stationRecords = ref<TrainStationEvent[]>([])
const allCommands = ref<DispatchCommandView[]>([])
const loadError = ref('')

async function loadStatic(): Promise<void> {
  try {
    const [planResult, routesResult, recordsResult, commandsResult] = await Promise.allSettled([
      dispatchApi.plan(),
      dispatchApi.routeList(),
      dispatchApi.stationRecords(),
      dispatchApi.commands()
    ])
    if (planResult.status === 'fulfilled') plan.value = planResult.value
    if (routesResult.status === 'fulfilled') routeList.value = routesResult.value
    if (recordsResult.status === 'fulfilled') stationRecords.value = recordsResult.value
    if (commandsResult.status === 'fulfilled') allCommands.value = commandsResult.value
    const firstError = [planResult, routesResult, recordsResult, commandsResult].find(
      (result): result is PromiseRejectedResult => result.status === 'rejected'
    )
    loadError.value = firstError ? String(firstError.reason instanceof Error ? firstError.reason.message : firstError.reason) : ''
  } catch (error) {
    loadError.value = error instanceof Error ? error.message : String(error)
  }
}

onMounted(() => void loadStatic())

/* ---------- 人工调度命令 ---------- */

const COMMAND_TYPES = [
  { value: 'HOLD', detail: 'text', hint: '扣车说明（信号折算为 MA/限速）' },
  { value: 'SPEED_LIMIT', detail: 'number', hint: '速度上限 m/s，例如 8.0' },
  { value: 'TEMP_SPEED_LIMIT', detail: 'number', hint: '临时限速 m/s' },
  { value: 'SPEED_FACTOR', detail: 'number', hint: '0-1 速度比例' },
  { value: 'REQUEST_ROUTE', detail: 'route', hint: '申请联锁建立进路（调度闭环）' },
  { value: 'REROUTE', detail: 'route', hint: '请求联锁改路' }
] as const

const form = ref({ trainId: '', commandType: 'SPEED_LIMIT', detail: '', routeId: '' })
const submitting = ref(false)

const selectedCommandMeta = computed(() => COMMAND_TYPES.find((item) => item.value === form.value.commandType))

async function submitCommand(): Promise<void> {
  if (!form.value.trainId || submitting.value) return
  submitting.value = true
  try {
    const body: Parameters<typeof dispatchApi.submitCommand>[0] = {
      trainId: form.value.trainId,
      commandType: form.value.commandType
    }
    if (selectedCommandMeta.value?.detail === 'route') {
      body.routeId = form.value.routeId
    } else if (form.value.detail) {
      body.detail = form.value.detail
    }
    const result = await dispatchApi.submitCommand(body)
    toastOk(
      `${dispatchCommandTypeLabel(result.commandType)} 已提交`,
      `命令 ${result.id} · 状态 ${dispatchCommandStatusLabel(result.status)}`
    )
    await loadStatic()
  } catch (error) {
    toastError('调度命令提交失败', error)
  } finally {
    submitting.value = false
  }
}

const cancelling = ref<string | null>(null)

async function cancelCommand(commandId: string): Promise<void> {
  cancelling.value = commandId
  try {
    const result = await dispatchApi.cancelCommand(commandId)
    if (result.accepted) toastOk('命令已撤销', commandId)
    else toastError('撤销未被接受', new Error(commandId))
    await loadStatic()
  } catch (error) {
    toastError('撤销失败', error)
  } finally {
    cancelling.value = null
  }
}

const cancellableStatuses = new Set(['PENDING', 'SENT', 'APPLIED'])

function formatTime(iso?: string | null): string {
  if (!iso) return '—'
  const date = new Date(iso)
  return Number.isNaN(date.getTime()) ? iso : date.toLocaleTimeString('zh-CN', { hour12: false })
}

const sortedRecords = computed(() =>
  [...stationRecords.value].sort((a, b) => (b.simulatedAt ?? '').localeCompare(a.simulatedAt ?? ''))
)

function delayTone(delaySeconds: number) {
  if (Math.abs(delaySeconds) <= 30) return 'ok' as const
  if (Math.abs(delaySeconds) <= 120) return 'warn' as const
  return 'danger' as const
}
</script>

<template>
  <div class="dispatch-workbench">
    <div class="status-strip">
      <StatusBadge tone="info" :label="`运行模式 ${runModeLabel(dispatch.runMode)}`" />
      <span class="strip-item">计划 <strong class="mono">{{ dispatch.planId || '—' }}</strong></span>
      <span class="strip-item">目标间隔 <strong class="num">{{ dispatch.targetHeadwaySeconds }}s</strong></span>
      <span class="strip-item">默认停站 <strong class="num">{{ dispatch.defaultDwellSeconds }}s</strong></span>
      <StatusBadge
        :tone="dispatch.interventionActive ? 'warn' : 'ok'"
        :label="dispatch.interventionActive ? '存在调度干预' : '无干预'"
      />
      <button type="button" class="btn sm" @click="loadStatic">刷新明细</button>
      <span v-if="loadError" class="load-error">{{ loadError }}</span>
    </div>

    <div class="wb-aligned">
      <div class="reused card-plan">
        <RunPlanPanel :plan="plan" :run-mode="dispatch.runMode" :target-headway-seconds="dispatch.targetHeadwaySeconds" />
      </div>
      <div class="reused">
        <HeadwayChart :profiles="dispatch.trainProfiles" :target-headway-seconds="dispatch.targetHeadwaySeconds" />
      </div>
      <div class="reused">
        <SelfRegulationPanel :profiles="dispatch.trainProfiles" />
      </div>
      <div class="reused">
        <StationHeadwayPanel :observations="dispatch.stationHeadways" />
      </div>
      <div class="reused">
        <DisturbanceList :disturbances="dispatch.openDisturbances" />
      </div>
      <div class="reused">
        <CommandTimeline :commands="dispatch.activeCommands" />
      </div>
    </div>

    <div class="wb-grid two">
      <Panel title="人工调度命令" subtitle="扣车/限速由信号折算为 MA 后下发；进路命令进入调度-联锁闭环">
        <form class="command-form" @submit.prevent="submitCommand">
          <label class="field">
            <span>目标列车</span>
            <select v-model="form.trainId" required>
              <option value="" disabled>选择列车</option>
              <option v-for="train in trains" :key="train.id" :value="train.id">{{ train.id }}</option>
            </select>
          </label>
          <label class="field">
            <span>命令类型</span>
            <select v-model="form.commandType">
              <option v-for="type in COMMAND_TYPES" :key="type.value" :value="type.value">
                {{ dispatchCommandTypeLabel(type.value) }}
              </option>
            </select>
          </label>
          <label v-if="selectedCommandMeta?.detail === 'route'" class="field">
            <span>进路</span>
            <select v-model="form.routeId" required>
              <option value="" disabled>选择进路</option>
              <option v-for="route in routeList" :key="route.routeId" :value="route.routeId">
                {{ route.routeId }} {{ route.name || '' }}（{{ route.fromStation }}→{{ route.toStation }}）
              </option>
            </select>
          </label>
          <label v-else class="field">
            <span>参数</span>
            <input v-model="form.detail" type="text" :placeholder="selectedCommandMeta?.hint" />
          </label>
          <button type="submit" class="btn primary" :disabled="submitting || !form.trainId">提交命令</button>
        </form>
        <p class="form-hint">{{ selectedCommandMeta?.hint }}</p>

        <h4 class="sub-title">命令历史</h4>
        <div class="scroll-y command-table">
          <table class="data">
            <thead>
              <tr>
                <th>命令</th>
                <th>类型</th>
                <th>列车</th>
                <th>状态</th>
                <th>创建</th>
                <th></th>
              </tr>
            </thead>
            <tbody>
              <tr v-if="allCommands.length === 0">
                <td colspan="6" class="empty">暂无命令</td>
              </tr>
              <tr v-for="command in allCommands" :key="command.id">
                <td class="mono small">{{ command.id }}</td>
                <td>{{ dispatchCommandTypeLabel(command.commandType) }}</td>
                <td class="mono">{{ command.regulatedTrainId || command.trainId }}</td>
                <td>
                  <StatusBadge :tone="commandStatusTone(command.status)" :label="dispatchCommandStatusLabel(command.status)" />
                </td>
                <td class="num">{{ formatTime(command.createdAt) }}</td>
                <td>
                  <button
                    v-if="cancellableStatuses.has(command.status)"
                    type="button"
                    class="btn sm danger"
                    :disabled="cancelling === command.id"
                    @click="cancelCommand(command.id)"
                  >
                    撤销
                  </button>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </Panel>

      <div class="right-col">
        <div class="reused">
          <RouteClosurePanel :decisions="dispatch.routeDecisions" :reservations="dispatch.routeReservations" />
        </div>

        <Panel title="到发记录" :subtitle="`${stationRecords.length} 条`" flush>
          <div class="scroll-y records-table">
            <table class="data">
              <thead>
                <tr>
                  <th>时间</th>
                  <th>列车</th>
                  <th>车站</th>
                  <th>事件</th>
                  <th>偏差</th>
                </tr>
              </thead>
              <tbody>
                <tr v-if="sortedRecords.length === 0">
                  <td colspan="5" class="empty">暂无到发记录</td>
                </tr>
                <tr v-for="(record, index) in sortedRecords" :key="index">
                  <td class="num">{{ formatTime(record.simulatedAt) }}</td>
                  <td class="mono">{{ record.trainId }}</td>
                  <td>{{ topology.stationName(record.stationId) }}</td>
                  <td>{{ stationEventTypeLabel(record.eventType) }}</td>
                  <td>
                    <StatusBadge
                      :tone="delayTone(record.delaySeconds ?? 0)"
                      :label="`${(record.delaySeconds ?? 0) > 0 ? '+' : ''}${record.delaySeconds ?? 0}s`"
                    />
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
        </Panel>
      </div>
    </div>
  </div>
</template>

<style scoped>
.dispatch-workbench {
  display: flex;
  flex-direction: column;
  gap: var(--gap-lg);
}

.status-strip {
  display: flex;
  align-items: center;
  gap: var(--gap-lg);
  flex-wrap: wrap;
  background: var(--bg-panel);
  border: 1px solid var(--border);
  border-radius: var(--radius-lg);
  padding: 10px 14px;
}

.strip-item {
  font-size: var(--fs-sm);
  color: var(--text-secondary);
}

.strip-item strong {
  color: var(--text-primary);
  margin-left: 4px;
}

.load-error {
  color: var(--status-danger);
  font-size: var(--fs-xs);
}

/* 固定 3×2 网格：同行卡片等高对齐（stretch），边框整齐无缺口；
   超高的"运行计划"卡限高并内部滚动，不再把整行撑出大片空白 */
.wb-aligned {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: var(--gap-lg);
  align-items: stretch;
}

@media (max-width: 1250px) {
  .wb-aligned {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 820px) {
  .wb-aligned {
    grid-template-columns: 1fr;
  }
}

.wb-aligned > .reused {
  display: flex;
  min-width: 0;
  min-height: 0;
}

/* 让复用组件的旧面板在网格单元内撑满高度 */
.wb-aligned > .reused :deep(section.panel) {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
}

/* 运行计划内容最长：限高 + 内部滚动，行高由图表卡决定 */
.wb-aligned > .card-plan :deep(section.panel) {
  max-height: 470px;
  overflow-y: auto;
}

.wb-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(340px, 1fr));
  gap: var(--gap-lg);
  align-items: start;
}

.wb-grid.two {
  grid-template-columns: minmax(0, 1.2fr) minmax(0, 1fr);
  align-items: stretch;
}

/* 右列末尾的到发记录卡吃掉剩余高度，与左侧命令卡底边对齐 */
.right-col > :last-child {
  flex: 1;
  min-height: 0;
}

@media (max-width: 1100px) {
  .wb-grid.two {
    grid-template-columns: 1fr;
  }
}

.right-col {
  display: flex;
  flex-direction: column;
  gap: var(--gap-lg);
}

.command-form {
  display: grid;
  grid-template-columns: 1fr 1fr 1fr auto;
  gap: var(--gap-md);
  align-items: end;
}

@media (max-width: 900px) {
  .command-form {
    grid-template-columns: 1fr 1fr;
  }
}

.form-hint {
  margin: 6px 0 0;
  font-size: var(--fs-xs);
  color: var(--text-muted);
}

.sub-title {
  margin-top: var(--gap-md);
  font-size: var(--fs-sm);
  color: var(--text-secondary);
}

.command-table {
  max-height: 280px;
}

.records-table {
  max-height: 320px;
}

.small {
  font-size: var(--fs-xs);
}

.empty {
  text-align: center;
  color: var(--text-muted);
}

/* ---- 复用组件暗色适配：覆盖旧浅色面板样式 ---- */
.dispatch-workbench .reused :deep(section.panel) {
  background: var(--bg-panel);
  border: 1px solid var(--border);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-panel);
}

.dispatch-workbench .reused :deep(h2) {
  color: var(--text-primary);
  font-size: var(--fs-md);
}

.dispatch-workbench .reused :deep(h3) {
  color: var(--text-primary);
}

.dispatch-workbench .reused :deep(.empty),
.dispatch-workbench .reused :deep(.meta),
.dispatch-workbench .reused :deep(.summary),
.dispatch-workbench .reused :deep(header span),
.dispatch-workbench .reused :deep(.heading > span),
.dispatch-workbench .reused :deep(.waiting),
.dispatch-workbench .reused :deep(.description),
.dispatch-workbench .reused :deep(p) {
  color: var(--text-secondary);
}

.dispatch-workbench .reused :deep(li),
.dispatch-workbench .reused :deep(article) {
  background: var(--bg-panel-raised);
  border-color: var(--border);
}

.dispatch-workbench .reused :deep(article[data-action='CATCH_UP']) {
  background: var(--status-warn-bg);
}

.dispatch-workbench .reused :deep(article[data-action='SLOW_DOWN']) {
  background: var(--status-danger-bg);
}

.dispatch-workbench .reused :deep(article[data-action='NORMAL']) {
  background: var(--status-ok-bg);
}

.dispatch-workbench .reused :deep(table th),
.dispatch-workbench .reused :deep(table td) {
  border-bottom-color: var(--border);
  color: var(--text-primary);
}

.dispatch-workbench .reused :deep(.service-list article),
.dispatch-workbench .reused :deep(.list article) {
  background: var(--bg-panel-raised);
}

.dispatch-workbench .reused :deep(small) {
  color: var(--text-muted);
}
</style>
