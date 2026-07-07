# Railway-Sim

上京地铁仿真系统项目初始化仓库。

本项目采用“中心总控 + 模块化单体 + 事件驱动 + 多车实体仿真”的架构。后端使用 Spring Boot，前端使用 Vue 3 + TypeScript，实时状态通过 WebSocket 推送，普通查询和控制使用 REST，线路和拓扑配置使用 YAML，历史数据和配置数据面向 MySQL 落库。

## 项目结构

```text
Railway-Sim
├─ backend        Spring Boot 后端，负责仿真时钟、业务模块、REST/WebSocket
├─ frontend       Vue 3 + TypeScript 前端，负责监控、调度和可视化界面
├─ config         线路、轨道、供电等 YAML 示例配置
├─ database       MySQL 表结构草案
└─ docs           架构、接口、协作和成员任务说明
```

## 快速启动

后端：

```bash
cd backend
mvn spring-boot:run
```

前端：

```bash
pnpm install
pnpm --filter railway-sim-frontend dev
```

默认地址：

- 后端 REST: `http://localhost:8080/api`
- 后端 WebSocket: `ws://localhost:8080/ws/simulation`
- 前端开发服务: `http://localhost:5173`

## 当前初始化范围

- 后端已建立仿真总控、统一时钟、列车实体管理、轨道占用、信号 MA、供电状态、监控快照和 WebSocket 推送骨架。
- 前端仅建立 Vue 3 + TypeScript 工程骨架、API 封装目录、类型目录和组件/视图目录，不预置具体界面实现。
- 文档已给出成员任务入口、模块边界、Git 协作方式和接口契约。

## 重要设计原则

本系统不采用“每辆列车一个微服务”的建模方式。列车以 `TrainEntity` 对象存在，由 `TrainManager` 在统一仿真时钟下批量更新，避免多服务通信、序列化和部署开销。

详细说明见 [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) 和 [docs/TEAM_GUIDE.md](docs/TEAM_GUIDE.md)。
