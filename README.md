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
- 后端已增加静态线路适配层，可从 `line-demo.yaml` 或线路 `.xls/.xlsx` 工作簿中提取区段、限速、坡度、道岔、车站/站台、信号机、应答器和进路等标准对象；当前多车 1D 运行环路通过线性投影消费这些数据。
- FMU 服务已建立 Modelica 模型草案、HTTP JSON 批量步进接口和 Python fallback 运行路径；默认后端仍关闭外部 FMU 服务，使用 Java 降级模型保持可运行。
- 前端仅建立 Vue 3 + TypeScript 工程骨架、API 封装目录、类型目录和组件/视图目录，不预置具体界面实现。
- 文档已给出成员任务入口、模块边界、Git 协作方式和接口契约。

## 静态线路数据入口

后端默认从 `../config/line-demo.yaml` 和 `../config/power_third_rail.yaml` 读取演示线路与供电数据。接入电子地图工作簿时，修改 `backend/src/main/resources/application.yml` 中的这两个配置：

```yaml
railway:
  simulation:
    line-data-path: /absolute/path/to/线路数据.xls
    power-config-path: ../config/power_third_rail.yaml
```

当前适配层会：

- 读取 `Seg表`、`静态限速表`、`坡度表`、`道岔表`、`车站表`、`站台表`、`信号机表`、`应答器表`、`进路表`
- 完成 `cm -> m`、`cm/s -> m/s` 的单位转换
- 过滤 `65535` 这类空值哨兵
- 为当前 `TrainState.positionMeters` 标量模型生成线性投影

这一步解决的是“系统可以接入原始线路工作簿”；后续如果要完整消费上下行、折返和分支图结构，需要在列车状态里引入显式路由和区段图定位，而不是只用单一标量里程。

## 重要设计原则

本系统不采用“每辆列车一个微服务”的建模方式。列车以 `TrainEntity` 对象存在，由 `TrainManager` 在统一仿真时钟下批量更新，避免多服务通信、序列化和部署开销。

详细说明见 [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) 和 [docs/TEAM_GUIDE.md](docs/TEAM_GUIDE.md)。
