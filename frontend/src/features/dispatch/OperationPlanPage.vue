<script setup lang="ts">
import DispatchView from '../../views/dispatch/DispatchView.vue'
import { useSimulation } from '../../composables/useSimulation'

/**
 * 团队运营计划页（views/dispatch/DispatchView.vue，lq 分支功能：
 * 运营进路计划 operation-plans / 信号计划发布 / 演示扰动）的路由包装器。
 * 数据由全局 store（经 useSimulation）供给，刷新请求回拉快照。
 */
const { plan, snapshot, dispatch, refreshSimulationSnapshot } = useSimulation()
</script>

<template>
  <div class="legacy-surface op-plan-wrap">
    <DispatchView
      :plan="plan"
      :snapshot="snapshot"
      :dispatch="dispatch"
      @refresh-requested="refreshSimulationSnapshot"
    />
  </div>
</template>

<style scoped>
.op-plan-wrap {
  margin: calc(-1 * var(--gap-lg));
  padding: var(--gap-lg);
  min-height: 100%;
}
</style>
