<script setup lang="ts">
import { computed, ref } from 'vue'
import { storeToRefs } from 'pinia'
import { simulationApi } from '../../api/rest'
import { useSimulationStore } from '../../stores/simulation'
import { useConnectionStore } from '../../stores/connection'
import Panel from '../../shared/components/Panel.vue'
import StatusBadge from '../../shared/components/StatusBadge.vue'
import { requestConfirm } from '../../shared/confirm'
import { toastError, toastOk } from '../../shared/toast'
import { simulationStatusLabel, occupancyLabel } from '../../shared/labels'
import type { SimulationSnapshot } from '../../types/simulation'

const simulation = useSimulationStore()
const connection = useConnectionStore()
const { status, tick, trackSegments } = storeToRefs(simulation)

interface OperationRecord {
  at: string
  action: string
  outcome: 'ok' | 'error'
  detail: string
}

const operationLog = ref<OperationRecord[]>([])
const busy = ref<string | null>(null)
const faultSegmentId = ref('')

const isRunning = computed(() => status.value === 'RUNNING')
const isPaused = computed(() => status.value === 'PAUSED')

function recordOperation(action: string, outcome: 'ok' | 'error', detail: string): void {
  operationLog.value.unshift({
    at: new Date().toLocaleTimeString('zh-CN', { hour12: false }),
    action,
    outcome,
    detail
  })
  operationLog.value = operationLog.value.slice(0, 30)
}

async function runControl(
  action: string,
  label: string,
  invoke: () => Promise<SimulationSnapshot>,
  options: { confirm?: { title: string; lines: string[] } } = {}
): Promise<void> {
  if (busy.value) return
  if (options.confirm) {
    const result = await requestConfirm({ ...options.confirm })
    if (!result.ok) return
  }
  busy.value = action
  try {
    const snapshot = await invoke()
    simulation.applySnapshot(snapshot)
    recordOperation(label, 'ok', `status=${snapshot.status} tick=${snapshot.tick}`)
    toastOk(`${label}成功`, `当前状态 ${simulationStatusLabel(snapshot.status)} · Tick ${snapshot.tick}`)
  } catch (error) {
    recordOperation(label, 'error', error instanceof Error ? error.message : String(error))
    toastError(`${label}失败`, error)
  } finally {
    busy.value = null
  }
}

const start = () => runControl('start', '启动仿真', () => simulationApi.start())
const pause = () => runControl('pause', '暂停仿真', () => simulationApi.pause())
const stopSim = () =>
  runControl('stop', '停止仿真', () => simulationApi.stop(), {
    confirm: { title: '停止仿真', lines: ['当前运行将被标记为 COMPLETED。', '确认停止？'] }
  })
const reset = () =>
  runControl('reset', '重置仿真', () => simulationApi.reset(), {
    confirm: {
      title: '重置仿真',
      lines: ['旧运行将标记为 CANCELLED_BY_RESET 并创建新的 simulationRunId。', '历史数据不会删除。确认重置？']
    }
  })
const singleStep = () => runControl('tick', '单步推进', () => simulationApi.tick())

async function injectFault(): Promise<void> {
  if (!faultSegmentId.value) return
  await runControl(
    'fault-inject',
    `注入区段故障 ${faultSegmentId.value}`,
    () => simulationApi.injectTrackFault(faultSegmentId.value),
    {
      confirm: {
        title: '注入区段故障',
        lines: [`目标区段：${faultSegmentId.value}`, '注入后前方信号将转红、MA 截断。', '确认注入？']
      }
    }
  )
}

async function clearFault(): Promise<void> {
  if (!faultSegmentId.value) return
  await runControl('fault-clear', `清除区段故障 ${faultSegmentId.value}`, () =>
    simulationApi.clearTrackFault(faultSegmentId.value)
  )
}

const statusTone = computed(() => {
  switch (status.value) {
    case 'RUNNING':
      return 'ok' as const
    case 'PAUSED':
      return 'warn' as const
    default:
      return 'neutral' as const
  }
})
</script>

<template>
  <div class="sim-control">
    <div class="grid">
      <Panel title="仿真状态">
        <div class="status-card">
          <StatusBadge :tone="statusTone" :label="simulationStatusLabel(status)" />
          <dl>
            <div>
              <dt>运行 ID</dt>
              <dd class="mono">{{ simulation.simulationRunId ?? '—' }}</dd>
            </div>
            <div>
              <dt>Tick</dt>
              <dd class="num">{{ tick }}</dd>
            </div>
            <div>
              <dt>仿真时间</dt>
              <dd class="num">{{ simulation.simulatedClock }}</dd>
            </div>
            <div>
              <dt>数据链路</dt>
              <dd>
                <StatusBadge
                  :tone="connection.linkQuality === 'up' ? 'ok' : connection.linkQuality === 'stale' ? 'stale' : 'danger'"
                  :label="connection.linkQuality === 'up' ? 'WebSocket 实时' : connection.linkQuality === 'stale' ? '数据过期' : '离线'"
                />
              </dd>
            </div>
          </dl>
          <p class="hint">
            启动后由后端 100ms 仿真时钟自动推进，前端每秒接收快照推送；“单步”仅在暂停/停止时使用。
          </p>
        </div>
      </Panel>

      <Panel title="控制操作">
        <div class="controls">
          <button type="button" class="btn primary" :disabled="isRunning || busy !== null" @click="start">▶ 启动</button>
          <button type="button" class="btn" :disabled="!isRunning || busy !== null" @click="pause">⏸ 暂停</button>
          <button type="button" class="btn" :disabled="(!isRunning && !isPaused) || busy !== null" @click="stopSim">⏹ 停止</button>
          <button type="button" class="btn danger" :disabled="busy !== null" @click="reset">↺ 重置</button>
          <button type="button" class="btn" :disabled="isRunning || busy !== null" :title="isRunning ? '运行中不可单步，请先暂停' : ''" @click="singleStep">
            ⏭ 单步 (+1 tick)
          </button>
        </div>
      </Panel>

      <Panel title="区段故障注入" subtitle="后端要求仿真非运行状态才可注入/清除">
        <div class="fault-panel">
          <label class="field">
            <span>目标区段</span>
            <select v-model="faultSegmentId" :disabled="isRunning">
              <option value="" disabled>选择区段</option>
              <option v-for="segment in trackSegments" :key="segment.id" :value="segment.id">
                {{ segment.id }}（{{ occupancyLabel(segment.occupancy) }}）
              </option>
            </select>
          </label>
          <div class="fault-actions">
            <button type="button" class="btn danger" :disabled="isRunning || !faultSegmentId || busy !== null" @click="injectFault">
              注入故障
            </button>
            <button type="button" class="btn" :disabled="isRunning || !faultSegmentId || busy !== null" @click="clearFault">
              清除故障
            </button>
          </div>
          <p v-if="isRunning" class="hint warn">仿真运行中——故障注入已禁用，请先暂停。</p>
        </div>
      </Panel>
    </div>

    <Panel title="操作记录（本会话）" flush>
      <div class="scroll-y op-log">
        <table class="data">
          <thead>
            <tr>
              <th>时间</th>
              <th>操作</th>
              <th>结果</th>
              <th>详情</th>
            </tr>
          </thead>
          <tbody>
            <tr v-if="operationLog.length === 0">
              <td colspan="4" class="empty">尚无操作</td>
            </tr>
            <tr v-for="(record, index) in operationLog" :key="index">
              <td class="num">{{ record.at }}</td>
              <td>{{ record.action }}</td>
              <td>
                <StatusBadge :tone="record.outcome === 'ok' ? 'ok' : 'danger'" :label="record.outcome === 'ok' ? '成功' : '失败'" />
              </td>
              <td class="mono detail-cell">{{ record.detail }}</td>
            </tr>
          </tbody>
        </table>
      </div>
    </Panel>
  </div>
</template>

<style scoped>
.sim-control {
  display: flex;
  flex-direction: column;
  gap: var(--gap-lg);
}

.grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(300px, 1fr));
  gap: var(--gap-lg);
}

.status-card {
  display: flex;
  flex-direction: column;
  gap: var(--gap-md);
}

dl {
  margin: 0;
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: var(--gap-md);
}

dl div {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

dt {
  font-size: var(--fs-xs);
  color: var(--text-muted);
}

dd {
  margin: 0;
  font-size: var(--fs-md);
  font-weight: 600;
}

.hint {
  margin: 0;
  font-size: var(--fs-xs);
  color: var(--text-muted);
}

.hint.warn {
  color: var(--status-warn);
}

.controls {
  display: flex;
  flex-wrap: wrap;
  gap: var(--gap-md);
}

.fault-panel {
  display: flex;
  flex-direction: column;
  gap: var(--gap-md);
}

.fault-actions {
  display: flex;
  gap: var(--gap-md);
}

.op-log {
  max-height: 260px;
}

.detail-cell {
  font-size: var(--fs-xs);
  color: var(--text-secondary);
  word-break: break-all;
}

.empty {
  text-align: center;
  color: var(--text-muted);
}
</style>
