# Vehicle Runtime Service

`vehicle-runtime-service` 是外部车辆侧运行时，默认监听 `9300`。推荐上线流程由车辆仿真系统调用 `POST /vehicle-runtime/trains/launch` 发起：先创建本车 `VehicleRuntimeInstance`，再唤醒 `VehicleControlQueue`，最后向中央 `/api/trains/runtime-registrations` 注册状态镜像。中央系统仍通过 HTTP 在后续 tick 中同步调用它，外部服务内部按每辆车维护一对队列：控制队列负责把 MA、轨道约束、供电约束转换为牵引/制动输入，仿真队列负责推进车辆纵向动力学。

供电仿真联动：中央 bootstrap 会下发 `powerNetworkBaseUrl` 和 `forwardPowerLoads`。只有中央车辆运行时模式为 `EXTERNAL_HTTP` 且供电仿真启用时，`forwardPowerLoads` 才应为 `true`；本服务会在 `step-fleet` 后把各供电分区的牵引功率、再生功率和取流电流推送到 `power-network-service` 的 `/power-network/state/query`，形成“车辆运行状态变化 -> 电网负荷变化”的网络链路。`DUAL_SHADOW` 只做影子计算，不写供电负荷。

接口：

```text
GET  /vehicle-runtime/health
POST /vehicle-runtime/bootstrap
POST /vehicle-runtime/trains/launch
PUT  /vehicle-runtime/trains/{trainId}
DELETE /vehicle-runtime/trains/{trainId}
DELETE /vehicle-runtime/trains
GET  /vehicle-runtime/instances
POST /vehicle-runtime/step-fleet
GET  /vehicle-runtime/events
```

启动列车示例：

```bash
curl -X POST http://127.0.0.1:9300/vehicle-runtime/trains/launch \
  -H 'Content-Type: application/json' \
  -d '{
    "trainId": "TR-003",
    "linkId": 12,
    "offsetMeters": 640.0,
    "direction": "DOWN",
    "registerWithCentral": true,
    "reason": "demo launch",
    "traceId": "runtime-train-003"
  }'
```

`central-base-url` 默认指向 `http://localhost:8080`。如果中央不在线，实例仍会在 9300 内创建并进入 `DEGRADED` 注册状态，后续可重新发起 launch 或由中央兼容接口同步。

运行：

```bash
cd vehicle-runtime-service
mvn spring-boot:run
```

测试：

```bash
cd vehicle-runtime-service
mvn test
```

当前实现已完成基础单元测试和中央 HTTP 集成测试；尚未进行长时间多车逻辑仿真、真实 FMU/RT-LAB 接入和压力验收。

未完整逻辑测试：`9300 + 9200 + backend` 三服务长时间联动、真实多车压力、真实 FMU/RT-LAB 物理模型替换和数据库持久化租约恢复尚未验收。当前服务状态均为内存态，没有复制中央数据库依赖。
