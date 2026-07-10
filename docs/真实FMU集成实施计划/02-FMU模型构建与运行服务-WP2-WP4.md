# 分计划02：FMU模型构建与运行服务（WP2～WP4）

> 上级计划：[真实车辆FMU集成实施计划](../真实FMU集成实施计划.md)
>
> 前置条件：分计划01的WP0、WP1全部通过。
>
> 执行边界：独立完成车辆FMU和9000运行服务，不接入9300生产物理循环。

## 1. 阶段目标

使用固定OpenModelica Docker工具链导出FMI 2.0 Co-Simulation车辆FMU，并在Linux/amd64容器内通过FMPy实现一份FMU、多列车独立实例的9000服务。

**阶段状态：[已完成]。9000已具备真实FMU执行能力，但按本分计划边界尚未接入9300生产物理循环。**

## 2. WP2：Modelica一维纵向模型

**状态：[已完成]；提交：`cf43b4c`。**

### 2.1 实施项

1. 按总计划第5节公式重写`TrainTractionBrake.mo`。
2. FMU内部维护位置、速度、累计受流电能和累计回馈电能。
3. `trainMassKg`、牵引/制动指令、坡度、网压、两类可用电功率、受流状态、车门和黏着系数每步输入。
4. 车门和紧急制动使用Boolean；故障使用Integer。
5. 输出机械牵引功率、受流电功率、机械再生功率和电网回馈功率，禁止口径复用。
6. MA、限速、站停和信号状态不进入FMU控制逻辑；曲线半径首版不参与公式。
7. 总制动力只在净力中扣除一次，再生不足部分由非再生制动补足。

### 2.2 必测公式

```text
P_motor_limit = min(3.2 MW, powerAvailableWatts * 0.88)
F_traction    = min(F_command, P_motor_limit / max(v, 0.5), adhesion * m * g)
P_grid        = F_traction * v / 0.88
P_regen_grid  = min(P_regen_candidate, 3.2 MW, regenAvailable / 0.35) * 0.35
F_resistance  = A + B*v + C*v^2
F_grade       = m*g*gradient
```

### 2.3 验证命令

```bash
docker run --rm --platform linux/amd64 \
  -v "$PWD:/workspace" -w /workspace \
  openmodelica/openmodelica@sha256:80fbff1a66fb6a6ade64a158415a45e022363249982c9f3ade07df2a369a357e \
  omc fmu-service/modelica/build_fmu.mos
```

### 2.4 退出条件与回滚

- OpenModelica无方程欠定、超定和单位错误。
- 零牵引、恒牵引、恒功率、坡道、常用/紧急制动、欠压和再生场景通过解析断言。
- 回滚只恢复Modelica源码并删除本地生成物，不影响Java fallback。

## 3. WP3：可复现FMU制品

**状态：[已完成]；提交：`2281c06`、`89bd127`。**

### 3.1 实施项

1. 拉取`openmodelica/openmodelica:v1.27.0-minimal`并记录RepoDigest。
2. 正式Dockerfile使用digest，不再使用浮动标签。
3. 增加`build_fmu.mos`、`scripts/build-fmu.sh`和多阶段Dockerfile。
4. 生成`fmu-service/build/TrainTractionBrake.fmu`、SHA-256和`fmu-manifest.json`。
5. 用FMPy验证FMI版本、Co-Simulation、linux64二进制和全部变量描述。
6. `.fmu`和构建目录加入`.gitignore`；Git只跟踪源码、构建脚本、清单和测试。

### 3.2 验证命令

```bash
docker pull --platform linux/amd64 openmodelica/openmodelica:v1.27.0-minimal
docker image inspect openmodelica/openmodelica:v1.27.0-minimal --format '{{index .RepoDigests 0}}'
./scripts/build-fmu.sh
shasum -a 256 fmu-service/build/TrainTractionBrake.fmu
docker build --no-cache --platform linux/amd64 \
  -f fmu-service/Dockerfile --target fmu-builder \
  -t railway-sim-fmu-builder-clean:local .
docker build --platform linux/amd64 \
  -f fmu-service/Dockerfile --target test \
  -t railway-sim-fmu-test:local .
docker run --rm --platform linux/amd64 --entrypoint python \
  railway-sim-fmu-test:local -m fmpy info /app/fmu/TrainTractionBrake.fmu
```

### 3.3 退出条件与回滚

- 清理缓存后能够重复导出并运行相同离线场景。
- `modelDescription.xml`的类型、单位、因果性和Value Reference全部通过映射校验。
- 回滚删除生成目录和镜像，不提交FMU二进制。

## 4. WP4：9000真实FMPy服务

**状态：[已完成]；提交：`1da5c00`。**

### 4.1 固定环境

- Linux/amd64、Python 3.12。
- `fmpy==0.3.30`。
- `fastapi==0.139.0`。
- `uvicorn[standard]==0.51.0`。
- 单进程Uvicorn worker。

### 4.2 实施项

1. 用FastAPI替换`BaseHTTPRequestHandler`，在线路径移除`SimpleFallbackModel`。
2. 服务启动时解包一次FMU并建立变量名到Value Reference的只读映射。
3. 每个`trainId`创建独立持久`FMU2Slave`。
4. 实现INIT、STEP、RESET、RESYNC、删除和全量重置。
5. 实现批次预检、同tick幂等、同tick冲突、乱序拒绝和参数哈希校验。
6. 单实例FMI失败进入`trainErrors`，不得阻塞其他实例。
7. 服务关闭和实例删除时执行`terminate/freeInstance`并清理临时目录。
8. 提供`/health`、`/fmu/metadata`、`/fmu/validate`及实例管理接口。

### 4.3 验证命令

```bash
docker build --platform linux/amd64 \
  -f fmu-service/Dockerfile --target test \
  -t railway-sim-fmu-test:local .
docker run --rm --platform linux/amd64 railway-sim-fmu-test:local
docker build --platform linux/amd64 \
  -f fmu-service/Dockerfile --target runtime \
  -t railway-sim-fmu-runtime:local .
docker run --rm --platform linux/amd64 -p 9000:9000 railway-sim-fmu-runtime:local
curl -fsS http://127.0.0.1:9000/health
curl -fsS http://127.0.0.1:9000/fmu/metadata
```

### 4.4 退出条件与回滚

- 两个不同初值实例连续100步互不污染。
- 生命周期、重复tick、冲突tick、乱序、参数不匹配和单实例错误全部符合总计划第7节。
- 9300尚未切换，回滚时停止9000即可。

## 5. 阶段提交与记录

| 工作包 | 提交号 | FMU哈希/模型版本 | 测试报告路径 | 结论 |
|---|---|---|---|---|
| WP2 | `cf43b4c` | `TrainTractionBrake/1.0.0` | `docs/真实FMU集成实施计划/验收记录/02-WP2-WP4验收记录.md` | 通过 |
| WP3 | `2281c06`、`89bd127` | 运行镜像`sha256:5b8c9ca70bf3ecd8e9bbd11a3262c8d413523a671bf4cc2cf6e40bf7ef5b635b` | 同上 | 通过 |
| WP4 | `1da5c00` | `TrainTractionBrake/1.0.0` | 同上 | 通过 |
