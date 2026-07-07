# Git 协作规范

## 分支建议

每个人从 `main` 拉自己的功能分支：

```bash
git checkout main
git pull
git checkout -b feature/monitor-view
```

推荐命名：

```text
feature/monitor-view
feature/dispatch-plan
feature/signal-track
feature/power-train
feature/api-contract
fix/websocket-reconnect
docs/team-guide
```

## 减少冲突的原则

1. 每个人主要修改自己的目录。
2. 公共接口先改文档，再改代码。
3. 不要把格式化全项目作为普通功能提交。
4. 一次提交只做一类事情。
5. 每天开始开发前先 `git pull`。
6. 提交前运行自己负责部分的检查。

## 目录归属

```text
frontend/src/views/monitor/                 综合监控负责人
frontend/src/views/dispatch/                运营调度负责人
frontend/src/views/signal-track/            信号轨道负责人
frontend/src/views/power-train/             供电车辆负责人
frontend/src/components/monitor/            综合监控组件
frontend/src/components/dispatch/           运营调度组件
frontend/src/components/signal-track/       信号轨道组件
frontend/src/components/power-train/        供电车辆组件
backend/src/main/java/com/railwaysim/train  车辆负责人
backend/src/main/java/com/railwaysim/power  供电负责人
backend/src/main/java/com/railwaysim/signal 信号负责人
backend/src/main/java/com/railwaysim/track  轨道负责人
backend/src/main/java/com/railwaysim/dispatch 调度负责人
backend/src/main/java/com/railwaysim/monitor 综合监控负责人
docs/API_CONTRACT.md                        组长统一维护
```

## 推荐提交节奏

```bash
git status
git add 你负责的文件
git commit -m "feat: add dispatch plan model"
git pull --rebase origin main
git push -u origin feature/dispatch-plan
```

## 合并建议

- 小功能尽快合并，避免大分支长期漂移。
- 合并前至少保证后端 `mvn test` 或前端 `pnpm build` 通过。
- 如果冲突集中在接口文件，由组长统一协调，不要多人同时抢改。
