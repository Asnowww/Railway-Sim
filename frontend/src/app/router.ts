import { createRouter, createWebHashHistory, type RouteRecordRaw } from 'vue-router'

/**
 * 一级路由 = 工作台。meta.nav 控制侧边导航显示；meta.debug 标记联调工具页。
 * 使用 hash 模式：产物可直接以静态文件方式部署（vite base:'./'），无需服务端回退配置。
 */
export const routes: RouteRecordRaw[] = [
  { path: '/', redirect: '/overview' },
  {
    path: '/overview',
    component: () => import('../features/overview/OverviewView.vue'),
    meta: { nav: '综合态势', icon: '◉' }
  },
  {
    path: '/sim',
    component: () => import('../features/sim-control/SimControlView.vue'),
    meta: { nav: '仿真控制', icon: '▶' }
  },
  {
    path: '/dispatch',
    component: () => import('../features/dispatch/DispatchWorkbench.vue'),
    meta: { nav: '调度运行', icon: '⇄' }
  },
  {
    path: '/vehicles',
    component: () => import('../features/vehicles/VehiclesView.vue'),
    meta: { nav: '车辆监控', icon: '▣' }
  },
  {
    path: '/cab',
    component: () => import('../features/cab/DriverCabView.vue'),
    meta: { nav: '司机台', icon: '◈' }
  },
  {
    path: '/signal-track',
    component: () => import('../features/signal-track/SignalTrackView.vue'),
    meta: { nav: '信号轨道', icon: '⌁' }
  },
  {
    path: '/power',
    component: () => import('../features/power/PowerView.vue'),
    meta: { nav: '供电能耗', icon: '⚡' }
  },
  {
    path: '/forecast',
    component: () => import('../features/forecast/ForecastView.vue'),
    meta: { nav: '客流预测', icon: '≈', simulated: true }
  },
  {
    path: '/ops',
    component: () => import('../features/ops/OpsView.vue'),
    meta: { nav: '告警运维', icon: '☰' }
  },
  {
    path: '/debug/dispatch-loop',
    component: () => import('../features/debug/DispatchLoopDebugPage.vue'),
    meta: { nav: '调度闭环', icon: '⚙', debug: true }
  },
  {
    path: '/debug/vehicle',
    component: () => import('../features/debug/VehicleDemoPage.vue'),
    meta: { nav: '车辆演示', icon: '⚙', debug: true }
  },
  {
    path: '/debug/interlock',
    component: () => import('../features/debug/InterlockDebugPage.vue'),
    meta: { nav: '联锁调试', icon: '⚙', debug: true }
  }
]

export const router = createRouter({
  history: createWebHashHistory(),
  routes
})
