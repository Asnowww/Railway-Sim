# Vehicle Runtime Service

`vehicle-runtime-service` 是外部车辆侧运行时，默认监听 `9300`。中央系统通过 HTTP 同步调用它，外部服务内部按每辆车维护一对队列：控制队列负责把 MA、轨道约束、供电约束转换为牵引/制动输入，仿真队列负责推进车辆纵向动力学。

接口：

```text
GET  /vehicle-runtime/health
POST /vehicle-runtime/bootstrap
PUT  /vehicle-runtime/trains/{trainId}
DELETE /vehicle-runtime/trains/{trainId}
DELETE /vehicle-runtime/trains
GET  /vehicle-runtime/instances
POST /vehicle-runtime/step-fleet
GET  /vehicle-runtime/events
```

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
