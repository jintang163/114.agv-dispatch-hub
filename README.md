# AGV 机器人任务调度中心（仓储多车调度）

从 0 到 1 实现的仓储 AGV 车队调度系统：接收 WMS 下发的搬运/拣选任务，按 **紧急度 + 截止时间** 排队派车，用 **A\* 寻路 + 时间窗预约** 消解多车路径冲突（路口碰撞 / 边对向冲突 / 追尾），支持 **高优先级任务安全抢占**、**故障/阻塞强制重分配**，并内置 **AGV 状态管理模块**（实时位置/速度/方向/电量/运行状态、车辆注册与车型载重维护、低电告警与自动回充），通过 MQTT 实时回传车队状态、Vue3 + ECharts 可视化。

## 技术栈

| 层 | 技术 |
|---|---|
| 后端 | Spring Boot 3.3 · Java 17 · Spring Data JPA · PostgreSQL |
| 调度存储 | Redis（优先级队列 ZSET + 时间窗预约表） |
| 消息 | MQTT（EMQX 5）· Spring Integration MQTT (Paho) |
| 前端 | Vue 3 · Vue Router · ECharts 5 · mqtt.js（WebSocket）· nginx |
| 仿真 | Python 3 + paho-mqtt（6 台虚拟 AGV） |
| 编排 | Docker Compose |

## 一键启动

```bash
docker compose up -d --build
```

| 服务 | 地址 | 说明 |
|---|---|---|
| 前端控制台 | http://localhost:8088 | 看板/任务/机器人/实时地图 |
| 后端 API | http://localhost:8080 | `/api/**` |
| EMQX Dashboard | http://localhost:18083 | admin / public |
| MQTT | tcp://localhost:1883 · ws://localhost:8083/mqtt | |
| PostgreSQL | localhost:5432 | agv / agv123 |
| Redis | localhost:6379 | |

启动后自动播种 **9×5 网格仓库**（6 个取货点 P-01~P-06、3 个卸货点 D-01~D-03、3 个充电桩 C-01~C-03、45 个路口）与 **6 台 AGV**（含型号/载重/归属充电桩，初始电量刻意分散以便演示自动回充）；模拟器容器启动后 AGV 上线变为空闲。

演示脚本（在宿主机执行，需 curl）：

```bash
bash demo/wms-demo.sh          # 下发普通任务 + 1 个高优急单，观察抢占
```

推荐手动演练路径：
1. **任务管理 → 下发任务**：连发 6 个低/中优任务占满 AGV，再发 1 个「高」优先级急单 → 队列置顶、某台**未载货** AGV 被抢占（任务时间线可见 `PREEMPTED`）。
2. **机器人 → 注入故障**：在途任务立即回到队列并被其他 AGV 接走（`EXCEPTION → REASSIGNED`），故障车可「恢复」。
3. **实时地图 → 点击某条通道**：阻塞后所有经过该边的在途任务 **A\* 自动绕行重规划**（红色虚线表示阻塞边）。
4. 给待分配任务在列表中直接切换优先级 → ZSET 立即重排，实现插队。

## 系统架构

```
        WMS ──HTTP──► TaskController ──► PostgreSQL(任务/事件/地图/机器人)
                                          │
                          ┌───────────────┴────────────────┐
                          ▼                                 ▼
                 PriorityTaskQueue(Redis ZSET)      ReservationStore(Redis ZSET)
                 紧急度+截止+老化 score             节点/边 时间窗（Lua 原子预约）
                          │                                 ▲
                   调度 tick(1s)                            │ 原子 check-and-add
                          ▼                                 │
   DispatchEngine ──► RobotSelector(就近/抢占) ──► AStarPlanner ──► TimelineBuilder
                          │
                          ├── MQTT agv/{code}/task ──► AGV(真机/模拟器)
                          └── MQTT fleet/state, fleet/events ──► 前端(WebSocket 直连 EMQX)
        AGV ──MQTT agv/{code}/status|event ──► 入站路由 ──► 状态机/心跳巡检
```

后端内部用单把 `ReentrantLock` 把「调度 tick / MQTT 入站事件 / REST 管控」串行化；Redis 侧用 Lua 脚本保证整条路径时间窗预约的原子性。

### 任务优先级队列（Redis ZSET）

`score = 优先级权重 × 1,000,000 + 截止紧迫度(0~144,000) + 排队老化(0~720)`

- 高/中/低权重为 3/2/1，档位差 100 万，**低优先级永不越级**；
- 同档内距期望完成时间越近分越高；排队每分钟 +1（720 封顶）**防饥饿**；
- tick 每 1s 重算全部 score，高优任务入队或调权立即置顶（插队）。

### 多机器人冲突消解（核心）

1. **A\* 寻路**：曼哈顿启发值 + 拓扑边权，规划「当前位置→取货点→卸货点」两段路径。
2. **时间窗预约表**：每个节点、每条边（对向规范化为同一 key `A>B`）在 Redis 中是一个 ZSET，成员为 `[到达,释放)` 时间窗。区间重叠判冲突，因此同时解决：
   - **路口碰撞**：同一节点同一时间窗只允许一台车；
   - **边对向冲突 / 正面相撞**：两个方向争抢同一条边的时间窗；
   - **追尾**：后车的节点窗与前车重叠即被拒。
3. **冲突等待**：预约失败时以 2s 为步长向后搜索最多 30s 的发车时刻，并把 `startDelay` 随任务下发，AGV 原地等待；仍失败则任务留队，下一 tick 重试。
4. **阻塞重规划**：通道被置为 blocked 后从拓扑剔除，受影响的在途任务立即 A\* 绕行并原子更换时间窗；绕行暂不可得时 AGV 原地等待，tick 持续重试。

### 高优抢占（安全约束）

仅当同时满足：新任务优先级严格更高、目标 AGV **尚未载货**（仍在去取货点途中）、且车辆已驶过当前边中点（可在前方节点干净接管）。被抢占任务立即释放时间窗、回到待分配队列；车辆先走完剩余半边（该半边边窗持续预约），再在接管节点并入高优路径。**载货任务不可抢占，必须送达。**

### 故障与心跳

- AGV 每 1s 上报 `status`（retained），超过 8s 无心跳 → 判定故障；
- 故障车任务：`EXCEPTION` 记录后立即回到 `PENDING` 强制重分配，时间窗释放，下一 tick 派给其他车；
- 支持人工注入故障/恢复（机器人页或 REST）。

### AGV 状态管理与自动回充

**实时状态**：每台 AGV 的运行状态为 `空闲 IDLE / 执行 BUSY / 充电 CHARGING / 故障 FAULT / 离线 OFFLINE`；`fleet/state` 快照包含站点（node/nextNode/progress）、地图坐标（边上插值 x/y）、**速度**、**航向角**（0=东，由拓扑推算或车端自报）、电量、载货、当前任务与阶段。

**注册与维护**：每台车维护 编号 code、型号 model、额定载重 payloadCapacity、允许任务类型 allowedTaskTypes（空=全部；派车时按类型与货物重量过滤）、归属充电桩 homeCharger。支持 REST 注册 / 更新 / 注销（仅离线无在途任务）。

**电量管理**（阈值可在 application.yml 调整）：

| 参数 | 默认 | 含义 |
|---|---|---|
| `agv.low-battery-threshold` | 25% | 空闲车低于该值自动生成充电任务回桩 |
| `agv.critical-battery-threshold` | 15% | 执行中车辆告警，不打断在途任务，送达后立即回充 |
| `agv.charge-target-battery` | 95% | 充至该电量结束充电任务，恢复空闲可派单 |
| `agv.charge-dwell-seconds` | 30s | 在桩充电停靠时长（也是充电桩预约占用时长） |

- 空闲低电车：电量巡检（2s）幂等生成 **CHARGING 任务**，归属桩优先、否则 A\* 就近选**未被占用**的充电桩，走统一队列派车与时间窗预约（一台桩同时只服务一台车）；
- 执行中严重低电：只发告警，**载货任务必须先送达**，完成时自动补一个回充任务；
- 充电任务绑定专属车辆，不可转派；车辆故障则取消（恢复后由巡检重新生成）；
- 机器人页可手动「回充」，并对低电/严重低电车行高亮；模拟器按行驶放电、在桩涨电，充满自动结束。

## 任务状态机

```
PENDING ──派车──► ASSIGNED ──TASK_STARTED──► EXECUTING ──完成──► COMPLETED
   ▲                │                            │
   │重分配          │取消                        │取消/失败
   └──── CANCELLED ◄┴────────────────────────────┘
   ▲
   └── EXCEPTION（故障/失联，记录后重回 PENDING 重分配）
```

## MQTT 主题约定

| 主题 | 方向 | 说明 |
|---|---|---|
| `agv/{code}/task` | ↓ 调度→车 | 任务派发：taskId/type/path/from/to/dwell/startDelay/preempt；充电任务另含 chargeDwell/targetBattery |
| `agv/{code}/cmd` | ↓ 调度→车 | CANCEL / PATH_UPDATE / FAULT / RESET |
| `agv/{code}/status` | ↑ 车→调度 | retained，1s：status(含 CHARGING)/node/nextNode/progress/phase/loaded/battery/speed/heading |
| `agv/{code}/event` | ↑ 车→调度 | TASK_STARTED/NODE_ARRIVED/PICKED_UP/DROPPED/CHARGING_STARTED/TASK_COMPLETED/PREEMPTED/FAULT/RECOVERED |
| `fleet/state` | 调度→前端 | retained，1s 全量车队快照 |
| `fleet/events` | 调度→前端 | 调度事件流（派发/抢占/重分配/异常…） |

## REST API 摘要

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/tasks` | WMS 下发任务 |
| GET | `/api/tasks?status=` | 任务列表 |
| GET | `/api/tasks/{id}` `/api/tasks/{id}/events` | 详情 / 事件时间线 |
| PUT | `/api/tasks/{id}/priority` | 调整优先级（插队） |
| POST | `/api/tasks/{id}/cancel` | 取消 |
| POST | `/api/tasks/{id}/reassign` | 强制重分配（body 可指定 robotCode） |
| GET | `/api/tasks/queue/view` | 优先级队列快照与评分 |
| GET | `/api/robots` · GET `/api/robots/chargers` | 机器人监控 / 充电桩列表 |
| POST | `/api/robots` · PUT `/api/robots/{code}` · DELETE `/api/robots/{code}` | AGV 注册 / 档案更新 / 注销（仅离线） |
| POST | `/api/robots/{code}/charge` | 手动触发回充（自动生成充电任务） |
| POST | `/api/robots/{code}/fault` `/recover` | 故障注入 / 恢复演练 |
| GET | `/api/map` · PUT `/api/map/edges/block` | 拓扑查询 / 通道阻塞 |
| GET | `/api/stats/overview` `/completion-trend` `/recent-events` | 看板统计 |

## 目录结构

```
backend/      Spring Boot 调度服务（domain 领域模型 / scheduler 调度核心 / infra.mqtt / api / service / bootstrap 种子数据）
emulator/     Python 虚拟 AGV（运动、取货停靠、抢占接管、取消/故障/改道）
frontend/     Vue3 SPA（Dashboard 看板、Tasks 任务、Robots 机器人、MapView 实时地图）
demo/         WMS 演示脚本
docker-compose.yml
```

## 本地开发（不用 Docker）

```bash
# 1. 基础设施
docker compose up -d postgres redis emqx
# 2. 后端
cd backend && mvn spring-boot:run
# 3. 模拟器
pip install -r emulator/requirements.txt
BROKER=tcp://localhost:1883 ROBOTS_URL=http://localhost:8080/api/robots python emulator/agv_emulator.py
# 4. 前端
cd frontend && npm install && npm run dev    # http://localhost:5173
```

## 说明与扩展方向

- 时间窗为固定步长（2s/边，取/卸货 4s），真实部署可改为 AGV 自报预计到达时间（ETA）驱动预约；
- 单调度实例够用；多实例可把 `ReentrantLock` 换为 Redis 分布式锁/分片；
- 已支持充电任务与低电量自动回充；可继续扩展：双向单行通道交通管制、按车型/工位的任务类型路由、WMS 对接鉴权与幂等（externalNo）。
