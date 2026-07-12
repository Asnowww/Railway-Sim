<script setup lang="ts">
import { defineAsyncComponent } from 'vue'
import CommandTimeline from '../../components/dispatch/CommandTimeline.vue'
import DisturbanceList from '../../components/dispatch/DisturbanceList.vue'
import RunPlanPanel from '../../components/dispatch/RunPlanPanel.vue'
import SelfRegulationPanel from '../../components/dispatch/SelfRegulationPanel.vue'
import type { DispatchSnapshot, RunPlanResponse } from '../../types/dispatch'
import type { SimulationSnapshot } from '../../types/simulation'

const HeadwayChart = defineAsyncComponent(() => import('../../components/dispatch/HeadwayChart.vue'))

defineProps<{
  plan: RunPlanResponse | null
  snapshot: SimulationSnapshot | null
  dispatch: DispatchSnapshot
}>()

const trainStatusLabel = (status: string) => {
  const labels: Record<string, string> = {
    RUNNING: '运行',
    DWELLING: '停站',
    EMERGENCY_BRAKE: '紧急制动',
    DEGRADED: '降级',
    FAULT: '故障',
  }
  return labels[status] ?? status
}

const dynamicsReasonLabel = (reason: string) => {
  const labels: Record<string, string> = {
    STATION_STOP_WINDOW: '站停窗口',
    STATION_APPROACH: '进站制动',
    MA_DISTANCE_LIMIT: '移动授权距离限制',
    MOVEMENT_AUTHORITY_EXHAUSTED: '移动授权已用尽',
    SPEED_LIMIT_EXCEEDED: '超过限速',
    SPEED_MARGIN_AVAILABLE: '可牵引加速',
    NEAR_TARGET_SPEED: '接近目标速度',
    TARGET_SPEED_REACHED: '达到目标速度',
    TRACTION_UNAVAILABLE: '牵引不可用',
  }
  return labels[reason] ?? reason
}
</script>

<template>
  <div class="dispatch-page">
    <div class="grid">
      <RunPlanPanel
        :plan="plan"
        :run-mode="dispatch.runMode"
        :target-headway-seconds="dispatch.targetHeadwaySeconds"
      />
      <HeadwayChart
        :profiles="dispatch.trainProfiles"
        :target-headway-seconds="dispatch.targetHeadwaySeconds"
      />
      <SelfRegulationPanel :profiles="dispatch.trainProfiles" />
      <DisturbanceList :disturbances="dispatch.openDisturbances" />
      <CommandTimeline :commands="dispatch.activeCommands" />
    </div>

    <section v-if="snapshot" class="trains">
      <h2>列车实时状态</h2>
      <table>
        <thead>
          <tr>
            <th>车次</th>
            <th>位置(m)</th>
            <th>速度(m/s)</th>
            <th>状态</th>
            <th>站点</th>
            <th>停站(s)</th>
            <th>限速(m/s)</th>
            <th>MA距离(m)</th>
            <th>车辆约束</th>
            <th>满载率</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="train in snapshot.trains" :key="train.id">
            <td>{{ train.id }}</td>
            <td>{{ train.positionMeters.toFixed(1) }}</td>
            <td>{{ train.speedMetersPerSecond.toFixed(1) }}</td>
            <td>{{ trainStatusLabel(train.status) }}</td>
            <td>{{ train.currentStationId || '-' }}</td>
            <td>{{ train.dwellElapsedSeconds ?? 0 }}</td>
            <td>{{ train.speedLimitMetersPerSecond.toFixed(1) }}</td>
            <td>{{ train.movementAuthorityDistanceMeters.toFixed(1) }}</td>
            <td>{{ dynamicsReasonLabel(train.dynamicsConstraintReason || '-') }}</td>
            <td>{{ ((train.loadRate ?? 0) * 100).toFixed(0) }}%</td>
          </tr>
        </tbody>
      </table>
    </section>
    <section v-else class="trains empty">
      <p>暂无列车数据。请使用页面顶部的「启动」和「步进」按钮。</p>
    </section>
  </div>
</template>

<style scoped>
.dispatch-page {
  display: grid;
  gap: 16px;
  padding: 16px 20px 20px;
  max-width: 1200px;
  margin: 0 auto;
}

.grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 16px;
}

.trains {
  background: #fff;
  border: 1px solid #e2e8f0;
  border-radius: 12px;
  padding: 16px;
}

.trains.empty p {
  margin: 0;
  color: #64748b;
  font-size: 14px;
}

.trains h2 {
  margin: 0 0 12px;
  font-size: 16px;
}

table {
  width: 100%;
  border-collapse: collapse;
  font-size: 13px;
}

th,
td {
  border-bottom: 1px solid #f1f5f9;
  padding: 8px 6px;
  text-align: left;
}

@media (max-width: 900px) {
  .grid {
    grid-template-columns: 1fr;
  }
}
</style>
