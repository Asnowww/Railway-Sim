# Railway-Sim

上京地铁仿真系统项目初始化仓库。

本项目采用“中心总控 + 模块化单体 + 事件驱动 + 多车实体仿真 + 局部 FMU 物理模型”的架构。后端使用 Spring Boot，前端使用 Vue 3 + TypeScript，车辆牵引/制动物理核心预留 Modelica/FMU 服务，实时状态通过 WebSocket 推送，普通查询和控制使用 REST，线路和拓扑配置使用 YAML/Excel 适配层导入，历史数据和配置数据面向 MySQL 落库。

## 项目结构

```text
Railway-Sim
├─ backend        Spring Boot 后端，负责仿真时钟、业务模块、REST/WebSocket
├─ frontend       Vue 3 + TypeScript 前端，负责监控、调度和可视化界面
├─ fmu-service    Python FMU 服务骨架，负责车辆牵引/制动物理批量步进
├─ config         线路、轨道、供电等 YAML 示例配置
├─ database       MySQL 表结构草案
└─ docs           架构、接口、协作和成员任务说明
```

## 快速启动

后端：

```bash
cd backend
mvn spring-boot:run -Dspring-boot.run.arguments="--spring.profiles.active=split"
```

供电仿真（必须先于后端启动）：

```bash
cd power-network-service
python3 -m venv .venv
.venv/bin/python -m pip install -r requirements.txt
.venv/bin/python -m app.http_server
```

车辆运行时：

```bash
cd vehicle-runtime-service
mvn spring-boot:run -Dspring-boot.run.arguments="--spring.profiles.active=split"
```

前端：

```bash
pnpm install
pnpm --filter railway-sim-frontend dev
```

FMU 服务骨架：

```bash
cd fmu-service
python3 -m app.http_server
```

默认地址：

- 后端 REST: `http://localhost:8080/api`
- 后端 WebSocket: `ws://localhost:8080/ws/simulation`
- 前端开发服务: `http://localhost:5173`
- FMU 服务: `http://localhost:9000/step-fleet`
- 供电仿真 OpenAPI: `http://localhost:9200/docs`

## 全栈验证

验证环境要求：Java 21、Maven、Node.js、`pnpm`、Docker，以及 Python 3.12。
9000 的本地工具环境和发布镜像均固定使用 Python 3.12；其他 Python 主次版本会被启动脚本拒绝。

全新检出后，执行一条命令安装测试依赖并依次运行 8080、9300、9200、9000 和前端五组验证：

```bash
PYTHON_312=python3.12 ./scripts/verify-all.sh --bootstrap
```

如果 Python 3.12 不在 `PATH`，将 `PYTHON_312` 指向对应解释器的绝对路径。9000 的 FMU 是 Linux/amd64 构建产物，因此 pytest 在与发布环境一致的 Docker test 镜像中运行；脚本仍会建立 `fmu-service/.venv` 并验证其中安装了 pytest，供本地静态检查和开发工具使用。

已完成依赖安装后可省略 `--bootstrap`：

```bash
./scripts/verify-all.sh
```

## 当前初始化范围

- 后端已建立仿真总控、统一时钟、列车实体管理、轨道占用、信号 MA、接触轨供电状态、TCMS/ATO 适配层、车辆物理端口、监控快照和 WebSocket 推送骨架。
- 后端已接入 **北京地铁9号线真实线路数据**（`config/line-m9.yaml`），13 站 16.5km，上下行双线独立运行，信号机闭塞区间按站间距切分为 47 段（24 上段 + 23 下段），前端态势图染色粒度均匀。
- 信号轨道模块已完成**调度→信号→车辆发车闭环**：四条运行线按时刻表全自动 DEPART，移除硬编码初始车，UP/DOWN 方向各自独立发车并被显式绑定到对应股道。
- 占用/预留/联锁联动已完成**同轨过滤和上下行隔离**：同公里标上下行互不阻挡 MA，预留段不溢到对向轨道，列车驶过后正确释放占用。
- **三层命令反馈闭环**已完成：信号接收回执（`POST /api/signal-track/command-receipt`）、每 tick 自动应用反馈（包含有效限速/MA 距离/约束来源）、统一反馈接口（`POST /api/dispatch/command-feedback`）。
- FMU 服务已建立 Modelica 模型草案、HTTP JSON 批量步进接口和 Python fallback 运行路径；默认后端仍关闭外部 FMU 服务，使用 Java 降级模型保持可运行。
- 前端已建立 Vue 3 + TypeScript 工程骨架、综合态势图、调度工作台、信号轨道调试面板、司机台等多视图。
- 文档已给出成员任务入口、模块边界、Git 协作方式和接口契约。

## 静态线路数据入口

后端默认从 `../config/line-m9.yaml` 和 `../config/power_third_rail.yaml` 读取北京地铁9号线真实数据与供电配置。也可切换为 `line-demo.yaml` 等其他演示线：

```yaml
railway:
  simulation:
    line-data-path: ../config/line-m9.yaml
    power-config-path: ../config/power_third_rail.yaml
```

当前适配层会：

- 读取区段、限速、坡度、道岔、车站/站台、信号机、应答器和进路
- 完成 `cm -> m`、`cm/s -> m/s` 的单位转换
- 过滤 `65535` 空值哨兵
- 自动计算 forwardNeighbor/sideNeighbor 拓扑邻居关系
- 支持 kmForwardMap（同轨公里标邻接图）用于上下行独立前向搜索

## 信号轨道 API 端点

| 端点 | 方法 | 说明 |
|------|------|------|
| `/api/signal-track/routes` | GET | 当前所有进路状态 |
| `/api/signal-track/route-events` | GET | 进路生命周期事件（状态迁移记录） |
| `/api/signal-track/dispatch-publications` | GET | 信号侧已接收的调度计划发布记录 |
| `/api/signal-track/dispatch-publications/latest` | GET | 最近一次调度计划发布 |
| `/api/signal-track/faults` | GET/POST | 故障区段查询与注入 |
| `/api/signal-track/command-receipt` | POST | 命令接收回执（反馈闭环） |
| `/api/dispatch/commands` | GET/POST | 调度命令下发与查询 |
| `/api/dispatch/command-feedback` | POST | 信号→调度统一反馈 |
| `/api/dispatch/route/list` | GET | 可用进路模板查询 |
| `/api/dispatch/signal-publications` | POST | 调度→信号发布发车计划 |

详细设计见 [docs/信号轨道模块详细设计文档.md](docs/信号轨道模块详细设计文档.md)。  
补充文档见 [docs/信号轨道-补充文档-问题排查与性能.md](docs/信号轨道-补充文档-问题排查与性能.md)。  
PPT 汇报要点见 [docs/信号轨道-PPT汇报要点.md](docs/信号轨道-PPT汇报要点.md)。

## 重要设计原则

本系统不采用“每辆列车一个微服务”的建模方式。列车以 `TrainEntity` 对象存在，由 `TrainManager` 在统一仿真时钟下批量更新，避免多服务通信、序列化和部署开销。

详细说明见 [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) 和 [docs/TEAM_GUIDE.md](docs/TEAM_GUIDE.md)。
