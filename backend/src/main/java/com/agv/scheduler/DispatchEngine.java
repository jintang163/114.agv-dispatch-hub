package com.agv.scheduler;

import com.agv.domain.entity.Robot;
import com.agv.domain.entity.Task;
import com.agv.domain.entity.TaskEvent;
import com.agv.domain.enums.RobotStatus;
import com.agv.domain.enums.TaskPhase;
import com.agv.domain.enums.TaskStatus;
import com.agv.domain.enums.TaskType;
import com.agv.infra.mqtt.FleetBroadcaster;
import com.agv.infra.mqtt.MqttGateway;
import com.agv.infra.mqtt.Topics;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 调度引擎（单进程内 ReentrantLock 串行化调度 tick 与 MQTT 事件处理）。
 *
 * 每个 tick：
 *  1. 清理过期时间窗；重算队列 score（截止时间临近、排队老化自动提权）
 *  2. 按 score 依次取待分配任务 → 选择最近空闲机器人（必要时安全抢占）
 *  3. A* 规划"位置→取货点→卸货点"，展开时空窗口
 *  4. 从 t0 起逐级延迟重试原子预约；成功即落库并经 MQTT 派发
 *  5. 预约失败的任务保留在队首，下个 tick 重试（冲突等待）
 */
@Slf4j
@Component
public class DispatchEngine {

    private static final int MAX_START_DELAY_SEC = 30;
    private static final int DELAY_STEP_SEC = 2;

    private final com.agv.domain.repository.TaskRepository taskRepository;
    private final com.agv.domain.repository.RobotRepository robotRepository;
    private final com.agv.domain.repository.TaskEventRepository eventRepository;
    private final PriorityTaskQueue queue;
    private final ReservationStore reservations;
    private final TopologyCache topology;
    private final AStarPlanner planner;
    private final RobotSelector selector;
    private final TimelineBuilder timelineBuilder;
    private final TelemetryStore telemetry;
    private final SchedulerProperties props;
    private final MqttGateway mqtt;
    private final FleetBroadcaster broadcaster;
    private final ChargingService charging;
    private final ObjectMapper om;
    private final ReentrantLock lock = new ReentrantLock();

    /** 等待重新规划的任务（机器人遇阻停在节点，tick 中重试） */
    private final Map<String, Long> pendingReplans = new HashMap<>();

    /** 严重低电标记：当前任务送达后立即回充（code -> 应回充的目标电量阈值判定结果） */
    private final Set<String> needChargeAfterTask = new HashSet<>();

    public DispatchEngine(com.agv.domain.repository.TaskRepository taskRepository,
                          com.agv.domain.repository.RobotRepository robotRepository,
                          com.agv.domain.repository.TaskEventRepository eventRepository,
                          PriorityTaskQueue queue,
                          ReservationStore reservations,
                          TopologyCache topology,
                          AStarPlanner planner,
                          RobotSelector selector,
                          TimelineBuilder timelineBuilder,
                          TelemetryStore telemetry,
                          SchedulerProperties props,
                          MqttGateway mqtt,
                          FleetBroadcaster broadcaster,
                          ChargingService charging,
                          ObjectMapper om) {
        this.taskRepository = taskRepository;
        this.robotRepository = robotRepository;
        this.eventRepository = eventRepository;
        this.queue = queue;
        this.reservations = reservations;
        this.topology = topology;
        this.planner = planner;
        this.selector = selector;
        this.timelineBuilder = timelineBuilder;
        this.telemetry = telemetry;
        this.props = props;
        this.mqtt = mqtt;
        this.broadcaster = broadcaster;
        this.charging = charging;
        this.om = om;
    }

    // ==================== 周期任务 ====================

    @Transactional
    @Scheduled(fixedDelayString = "${agv.dispatch-tick-ms:1000}")
    public void tick() {
        if (!lock.tryLock()) {
            return; // 上一 tick / 事件处理仍在进行
        }
        try {
            reservations.pruneExpired();
            retryPendingReplans();

            List<Task> pending = taskRepository.findByStatusOrderByCreatedAtAsc(TaskStatus.PENDING);
            queue.rescoreAll(pending, Instant.now());

            for (Long taskId : queue.orderedIds()) {
                Task task = taskRepository.findById(taskId).orElse(null);
                if (task == null || task.getStatus() != TaskStatus.PENDING) {
                    queue.remove(taskId);
                    continue;
                }
                // 充电任务为系统为指定车辆生成，只允许派给该车（vehicle-directed）
                String forceRobotCode = null;
                if (task.getType() == TaskType.CHARGING && task.getRobotId() != null) {
                    Robot bound = robotRepository.findById(task.getRobotId()).orElse(null);
                    if (bound == null) {
                        // 车辆已注销：取消悬空充电任务，释放充电桩占用
                        queue.remove(taskId);
                        task.setStatus(TaskStatus.CANCELLED);
                        task.setRemark("车辆已注销，充电任务自动取消");
                        taskRepository.save(task);
                        continue;
                    }
                    if (bound.getStatus() == RobotStatus.OFFLINE || bound.getStatus() == RobotStatus.FAULT) {
                        continue; // 等待该车恢复（故障时 handleFault 会清理，恢复后由巡检重新生成）
                    }
                    forceRobotCode = bound.getCode();
                }
                dispatchOne(task, forceRobotCode);
            }
        } catch (Exception e) {
            log.error("dispatch tick error", e);
        } finally {
            lock.unlock();
        }
    }

    /** AGV 心跳超时巡检：超时即判故障，其任务强制重分配 */
    @Transactional
    @Scheduled(fixedDelay = 2000)
    public void heartbeatSweep() {
        if (!lock.tryLock()) {
            return;
        }
        try {
            Instant cutoff = Instant.now().minusMillis(props.getHeartbeatTimeoutMs());
            for (Robot r : robotRepository.findAllByOrderByCodeAsc()) {
                if (r.getStatus() == RobotStatus.OFFLINE || r.getStatus() == RobotStatus.FAULT) {
                    continue;
                }
                if (r.getLastHeartbeat() != null && r.getLastHeartbeat().isBefore(cutoff)) {
                    log.warn("机器人 {} 心跳超时，判定故障", r.getCode());
                    broadcaster.event("FAULT", "WARN", r.getCode(), null,
                            "心跳超时(" + props.getHeartbeatTimeoutMs() + "ms)，判定故障");
                    handleFault(r, "心跳超时");
                }
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * 电量巡检（与调度 tick 同锁串行）：
     *  - IDLE 且低电：自动生成充电任务回桩（A* 选最近空闲桩），不派普通任务；
     *  - BUSY 且严重低电：仅告警，不打断在途任务，送达后立即回充；
     *  - CHARGING 状态由充电任务自身管理，此处不重复触发。
     */
    @Transactional
    @Scheduled(fixedDelayString = "${agv.battery-sweep-ms:2000}")
    public void batterySweep() {
        if (!lock.tryLock()) {
            return;
        }
        try {
            for (Robot r : robotRepository.findAllByOrderByCodeAsc()) {
                if (r.getBattery() == null) {
                    continue;
                }
                if (r.getStatus() == RobotStatus.IDLE) {
                    if (charging.shouldAutoCharge(r) && !charging.hasActiveChargeTask(r)) {
                        log.info("机器人 {} 电量 {}% 低于阈值 {}%，自动回充",
                                r.getCode(), r.getBattery(), props.getLowBatteryThreshold());
                        Task chargeTask = charging.requestCharge(r,
                                "低电量自动回充（阈值 " + props.getLowBatteryThreshold() + "%）");
                        if (chargeTask == null) {
                            charging.notifyNoChargerThrottled(r);
                        }
                    }
                } else if (r.getStatus() == RobotStatus.BUSY) {
                    if (charging.isCritical(r)) {
                        if (needChargeAfterTask.add(r.getCode())) {
                            log.warn("机器人 {} 严重低电 {}%，完成当前任务后回充",
                                    r.getCode(), r.getBattery());
                            broadcaster.event("LOW_BATTERY", "WARN", r.getCode(), r.getCurrentTaskId(),
                                    r.getCode() + " 严重低电 " + r.getBattery()
                                            + "%，将在当前任务送达后自动回充");
                        }
                    } else if (r.getBattery() >= props.getLowBatteryThreshold()) {
                        needChargeAfterTask.remove(r.getCode());
                    }
                }
            }
        } finally {
            lock.unlock();
        }
    }

    /** 手动触发回充（机器人页"立即充电"），返回生成/已有的充电任务 */
    @Transactional
    public Task requestCharge(String code) {
        lock.lock();
        try {
            Robot r = robotRepository.findByCode(code)
                    .orElseThrow(() -> new com.agv.common.ApiException("机器人不存在: " + code));
            if (r.getStatus() == RobotStatus.OFFLINE || r.getStatus() == RobotStatus.FAULT) {
                throw new com.agv.common.ApiException("机器人 " + code + " 离线/故障，无法回充");
            }
            if (charging.hasActiveChargeTask(r)) {
                throw new com.agv.common.ApiException("机器人 " + code + " 已有充电任务");
            }
            if (r.getStatus() == RobotStatus.CHARGING) {
                throw new com.agv.common.ApiException("机器人 " + code + " 正在充电");
            }
            Task t = charging.requestCharge(r, "人工触发回充");
            if (t == null) {
                throw new com.agv.common.ApiException("暂无空闲/可达充电桩");
            }
            return t;
        } finally {
            lock.unlock();
        }
    }

    // ==================== 派发 ====================

    @Transactional
    public void dispatchOne(Task task, String forceRobotCode) {
        RobotSelector.Candidate candidate = selector.select(task, forceRobotCode);
        if (candidate == null) {
            return; // 无可用机器人，留在队列
        }
        Robot robot = candidate.robot();
        boolean isCharging = task.getType() == TaskType.CHARGING;

        // 抢占接管点：车辆已在边的后半段，以前方节点为起点（模拟器先走完剩余半边）
        boolean preempt = candidate.preempt();
        RobotTelemetry rt = telemetry.get(robot.getCode());
        String physPos = positionOf(robot);
        String pos = preempt ? selector.preemptStart(robot) : physPos;
        // 充电任务从当前位置直达充电桩（fromNode 仅为创建时锚点，抢占后可能已移动）
        List<String> leg1;
        List<String> leg2;
        if (isCharging) {
            leg1 = planner.plan(pos, task.getToNode());
            leg2 = List.of(task.getToNode());
        } else {
            leg1 = planner.plan(pos, task.getFromNode());
            leg2 = planner.plan(task.getFromNode(), task.getToNode());
        }
        if (leg1.isEmpty() || leg2.isEmpty()) {
            task.setStatus(TaskStatus.EXCEPTION);
            task.setRemark("无可达路径: " + pos + "->" + task.getFromNode() + "->" + task.getToNode());
            taskRepository.save(task);
            queue.remove(task.getId());
            recordEvent(task.getId(), "EXCEPTION", robot.getCode(), "无可达路径");
            broadcaster.event("EXCEPTION", "ERROR", robot.getCode(), task.getId(), "任务无可达路径");
            return;
        }

        long now = Instant.now().getEpochSecond();
        Long victimId = preempt && candidate.victim() != null
                ? candidate.victim().getId() : null;

        // 抢占剩余半边：接管前车还在 node->nextNode 的后半段，继续占用该边
        String halfEdgeKey = null;
        long halfEdgeSeconds = 0;
        if (preempt && rt != null && rt.nextNode() != null && rt.progress() < 1.0) {
            halfEdgeKey = topology.edgeKey(rt.node(), rt.nextNode());
            halfEdgeSeconds = Math.max(1L, Math.round(Math.ceil(
                    props.getStepSeconds() * (1.0 - rt.progress()))));
        }

        // 充电任务：连接点不取货停靠，终点停靠时长 = 在桩充电时长
        boolean pickupAtJoin = !isCharging;
        int endDwell = isCharging ? props.getChargeDwellSeconds() : props.getDropDwellSeconds();

        List<ReservationStore.Window> windows = null;
        long chosenDelay = -1;
        for (int delay = 0; delay <= MAX_START_DELAY_SEC; delay += DELAY_STEP_SEC) {
            // 全程从 now 起预约：等待期间持续占用起点；抢占先走完剩余半边
            List<ReservationStore.Window> candidateWindows = timelineBuilder.build(
                    leg1, leg2, now, delay, halfEdgeKey, halfEdgeSeconds,
                    pickupAtJoin, endDwell);
            // 抢占时新窗口必然覆盖被抢占任务的旧窗口，检查阶段先忽略二者，
            // 选定延迟后先释放旧任务，再原子写入新窗口
            List<Long> ignore = victimId == null
                    ? List.of(task.getId()) : List.of(task.getId(), victimId);
            if (reservations.freeWindowsIgnoringTasks(ignore, candidateWindows)) {
                windows = candidateWindows;
                chosenDelay = delay;
                break;
            }
        }
        if (windows == null) {
            log.debug("任务 {} 与既有时间窗冲突，等待下一 tick", task.getId());
            return; // 冲突等待
        }

        // ---- 抢占：先释放被牺牲任务的预约并回队列 ----
        if (victimId != null) {
            Task victim = candidate.victim();
            reservations.releaseTask(victim.getId());
            victim.setStatus(TaskStatus.PENDING);
            victim.setRobotId(null);
            victim.setPhase(null);
            victim.setPlannedPath(List.of());
            taskRepository.save(victim);
            queue.enqueue(victim);
            recordEvent(victim.getId(), "PREEMPTED", robot.getCode(),
                    "被高优先级任务 #" + task.getId() + " 抢占，重回队列");
            broadcaster.event("PREEMPTED", "WARN", robot.getCode(), victim.getId(),
                    "任务被高优 #" + task.getId() + " 抢占");
        }

        // 原子写入新任务窗口（JVM 内同锁串行，dry-run 后无其他写者）
        if (!reservations.reserveWindows(robot.getCode(), task.getId(), windows)) {
            log.warn("任务 {} 预约写入竞态失败，下一 tick 重试", task.getId());
            return;
        }

        List<String> fullPath = joinPaths(leg1, leg2);

        task.setStatus(TaskStatus.ASSIGNED);
        task.setRobotId(robot.getId());
        task.setAssignedAt(Instant.now());
        task.setPhase(isCharging ? TaskPhase.GOING_CHARGER : TaskPhase.GOING_PICKUP);
        task.setPlannedPath(fullPath);
        task.setActualPath(new ArrayList<>(List.of(physPos)));
        if (task.getRemark() == null) {
            task.setRemark("");
        }
        taskRepository.save(task);
        queue.remove(task.getId());

        robot.setStatus(RobotStatus.BUSY);
        robot.setCurrentTaskId(task.getId());
        robot.setCurrentNode(physPos);
        robotRepository.save(robot);

        String actionText = isCharging ? "前往充电桩" : "分配";
        recordEvent(task.getId(),
                candidate.preempt() ? "PREEMPT_ASSIGN" : "ASSIGNED",
                robot.getCode(),
                (candidate.preempt() ? "抢占分配" : actionText)
                        + "，延迟 " + chosenDelay + "s 发车，路径 " + fullPath);
        broadcaster.event(candidate.preempt() ? "PREEMPT" : (isCharging ? "CHARGE_DISPATCH" : "ASSIGN"),
                candidate.preempt() ? "WARN" : "INFO",
                robot.getCode(), task.getId(),
                isCharging
                        ? robot.getCode() + " 前往充电桩 " + task.getToNode()
                        : (candidate.preempt() ? "抢占执行高优任务 #" : "开始执行任务 #") + task.getId());

        // 模拟器走完剩余半边(halfEdgeSeconds)后再避让等待 chosenDelay
        publishTaskToRobot(robot.getCode(), task, fullPath,
                chosenDelay + halfEdgeSeconds, victimId);
    }

    private void publishTaskToRobot(String robotCode, Task task, List<String> path,
                                    long startDelaySeconds, Long preemptTaskId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("taskId", task.getId());
        payload.put("type", task.getType().name());
        payload.put("from", task.getFromNode());
        payload.put("to", task.getToNode());
        payload.put("path", path);
        payload.put("startDelay", startDelaySeconds);
        payload.put("pickupDwell", props.getPickupDwellSeconds());
        payload.put("dropDwell", props.getDropDwellSeconds());
        if (task.getType() == TaskType.CHARGING) {
            payload.put("chargeDwell", props.getChargeDwellSeconds());
            payload.put("targetBattery", props.getChargeTargetBattery());
        }
        payload.put("stepSeconds", props.getStepSeconds());
        if (preemptTaskId != null) {
            payload.put("preempt", true);
            payload.put("preemptTaskId", preemptTaskId);
        }
        mqtt.send(Topics.task(robotCode), toJson(payload));
    }

    // ==================== AGV 上行事件 ====================

    /** 处理 agv/{code}/event */
    @Transactional
    public void handleRobotEvent(String code, String event, Map<String, Object> data) {
        lock.lock();
        try {
            Robot robot = robotRepository.findByCode(code).orElse(null);
            if (robot == null) {
                log.warn("未知机器人上报事件: {}", code);
                return;
            }
            Long taskId = longVal(data.get("taskId"));
            switch (event) {
                case "TASK_ACCEPTED" -> log.info("{} 接受任务 {}", code, taskId);
                case "TASK_STARTED" -> markStarted(robot, taskId);
                case "NODE_ARRIVED" -> appendActualNode(robot, taskId, str(data.get("node")));
                case "PICKED_UP" -> updatePhase(robot, taskId, TaskPhase.GOING_DELIVERY, "已取货");
                case "DROPPED" -> updatePhase(robot, taskId, TaskPhase.AT_DELIVERY, "已卸货");
                case "CHARGING_STARTED" -> updatePhase(robot, taskId, TaskPhase.CHARGING,
                        "已接入充电桩，充电中");
                case "TASK_COMPLETED" -> completeTask(robot, taskId);
                case "TASK_FAILED" -> {
                    broadcaster.event("EXCEPTION", "ERROR", code, taskId,
                            "任务失败: " + str(data.get("reason")));
                    handleFault(robot, str(data.get("reason")));
                }
                case "PREEMPTED" -> log.info("{} 确认被抢占，原任务 {}", code, taskId);
                case "FAULT" -> handleFault(robot, str(data.get("reason")));
                case "RECOVERED" -> recover(robot);
                case "NODE_BLOCKED" -> {
                    if (taskId != null) {
                        pendingReplans.put(code, taskId);
                        broadcaster.event("REROUTE", "WARN", code, taskId,
                                "节点/边受阻，请求重新规划");
                    }
                }
                default -> log.debug("未处理事件 {} from {}", event, code);
            }
        } finally {
            lock.unlock();
        }
    }

    @Transactional
    public void markStarted(Robot robot, Long taskId) {
        if (taskId == null) return;
        taskRepository.findById(taskId).ifPresent(t -> {
            if (t.getStatus() == TaskStatus.ASSIGNED) {
                t.setStatus(TaskStatus.EXECUTING);
                t.setStartedAt(Instant.now());
                taskRepository.save(t);
                recordEvent(taskId, "STARTED", robot.getCode(), "开始执行");
            }
        });
    }

    @Transactional
    public void appendActualNode(Robot robot, Long taskId, String node) {
        if (taskId == null || node == null) return;
        taskRepository.findById(taskId).ifPresent(t -> {
            List<String> actual = t.getActualPath() == null ? new ArrayList<>() : t.getActualPath();
            if (actual.isEmpty() || !node.equals(actual.get(actual.size() - 1))) {
                actual.add(node);
                t.setActualPath(actual);
                taskRepository.save(t);
            }
            if (node.equals(t.getFromNode()) && t.getPhase() == TaskPhase.GOING_PICKUP) {
                t.setPhase(TaskPhase.AT_PICKUP);
                taskRepository.save(t);
            }
        });
    }

    @Transactional
    public void updatePhase(Robot robot, Long taskId, TaskPhase phase, String detail) {
        if (taskId == null) return;
        taskRepository.findById(taskId).ifPresent(t -> {
            t.setPhase(phase);
            taskRepository.save(t);
            recordEvent(taskId, phase.name(), robot.getCode(), detail);
        });
    }

    @Transactional
    public void completeTask(Robot robot, Long taskId) {
        if (taskId == null) return;
        Task task = taskRepository.findById(taskId).orElse(null);
        if (task == null || task.getStatus() == TaskStatus.COMPLETED) {
            releaseAndIdle(robot, taskId);
            return;
        }
        boolean wasCharging = task.getType() == TaskType.CHARGING;
        reservations.releaseTask(taskId);
        task.setStatus(TaskStatus.COMPLETED);
        task.setPhase(TaskPhase.DONE);
        task.setCompletedAt(Instant.now());
        taskRepository.save(task);
        recordEvent(taskId, "COMPLETED", robot.getCode(),
                wasCharging ? "充电完成，电量 " + robot.getBattery() + "%" : "任务完成");
        if (wasCharging) {
            broadcaster.event("CHARGE_COMPLETE", "SUCCESS", robot.getCode(), taskId,
                    robot.getCode() + " 充电完成，电量恢复至 " + robot.getBattery() + "%");
        } else {
            broadcaster.event("COMPLETED", "SUCCESS", robot.getCode(), taskId, "任务完成");
        }
        needChargeAfterTask.remove(robot.getCode());
        releaseAndIdle(robot, taskId);
    }

    private void releaseAndIdle(Robot robot, Long taskId) {
        reservations.releaseTask(taskId);
        robot.setStatus(RobotStatus.IDLE);
        robot.setCurrentTaskId(null);
        robotRepository.save(robot);
    }

    // ==================== 故障 / 恢复 / 取消 / 重分配 ====================

    @Transactional
    public void handleFault(Robot robot, String reason) {
        Long taskId = robot.getCurrentTaskId();
        robot.setStatus(RobotStatus.FAULT);
        robot.setCurrentTaskId(null);
        robotRepository.save(robot);

        if (taskId != null) {
            Task task = taskRepository.findById(taskId).orElse(null);
            if (task != null && task.getStatus() != TaskStatus.COMPLETED
                    && task.getStatus() != TaskStatus.CANCELLED) {
                // 充电任务不可转派给其他车辆：故障即取消，恢复后由电量巡检重新生成
                if (task.getType() == TaskType.CHARGING) {
                    reservations.releaseTask(taskId);
                    queue.remove(taskId);
                    task.setStatus(TaskStatus.CANCELLED);
                    task.setRemark("AGV 故障，充电任务取消: " + reason);
                    taskRepository.save(task);
                    recordEvent(taskId, "CANCELLED", robot.getCode(), "AGV 故障，充电任务取消");
                    broadcaster.event("CANCELLED", "WARN", robot.getCode(), taskId, "故障导致充电任务取消");
                } else {
                    reservations.releaseTask(taskId);
                    task.setStatus(TaskStatus.EXCEPTION);
                    task.setRemark("AGV 故障: " + reason);
                    taskRepository.save(task);
                    recordEvent(taskId, "EXCEPTION", robot.getCode(), "AGV 故障: " + reason);

                    // 强制重分配：立即回到待分配队列，下一 tick 指派其他机器人
                    task.setStatus(TaskStatus.PENDING);
                    task.setRobotId(null);
                    task.setPhase(null);
                    task.setPlannedPath(List.of());
                    task.setRemark("原机器人 " + robot.getCode() + " 故障(" + reason + ")，等待重分配");
                    taskRepository.save(task);
                    queue.enqueue(task);
                    recordEvent(taskId, "REASSIGNED", robot.getCode(), "故障强制重分配，重回队列");
                    broadcaster.event("REASSIGN", "WARN", null, taskId,
                            robot.getCode() + " 故障，任务重新分配");
                }
            }
        }

        // 清理绑定该车但尚未派出（PENDING）的充电任务，避免悬空占用充电桩
        for (Task pendingCharge : taskRepository.findByRobotIdAndStatusIn(
                robot.getId(), List.of(TaskStatus.PENDING))) {
            if (pendingCharge.getType() == TaskType.CHARGING
                    && !Objects.equals(pendingCharge.getId(), taskId)) {
                queue.remove(pendingCharge.getId());
                pendingCharge.setStatus(TaskStatus.CANCELLED);
                pendingCharge.setRemark("AGV 故障，待派充电任务取消: " + reason);
                taskRepository.save(pendingCharge);
            }
        }
    }

    @Transactional
    public void recover(Robot robot) {
        robot.setStatus(RobotStatus.IDLE);
        robot.setCurrentTaskId(null);
        robotRepository.save(robot);
        broadcaster.event("RECOVERED", "INFO", robot.getCode(), null, "机器人恢复在线");
    }

    @Transactional
    public void cancelTask(Long taskId) {
        lock.lock();
        try {
            Task task = taskRepository.findById(taskId)
                    .orElseThrow(() -> new com.agv.common.ApiException("任务不存在: " + taskId));
            if (task.getStatus() == TaskStatus.COMPLETED
                    || task.getStatus() == TaskStatus.CANCELLED) {
                throw new com.agv.common.ApiException("任务已结束，无法取消");
            }
            if (task.getStatus() == TaskStatus.PENDING) {
                queue.remove(taskId);
            } else {
                reservations.releaseTask(taskId);
                if (task.getRobotId() != null) {
                    robotRepository.findById(task.getRobotId()).ifPresent(r -> {
                        mqtt.send(Topics.cmd(r.getCode()),
                                "{\"type\":\"CANCEL\",\"taskId\":" + taskId + "}");
                        if (Objects.equals(r.getCurrentTaskId(), taskId)) {
                            r.setStatus(RobotStatus.IDLE);
                            r.setCurrentTaskId(null);
                            robotRepository.save(r);
                        }
                    });
                }
            }
            task.setStatus(TaskStatus.CANCELLED);
            taskRepository.save(task);
            recordEvent(taskId, "CANCELLED", null, "人工/WMS 取消");
            broadcaster.event("CANCELLED", "WARN", null, taskId, "任务已取消");
        } finally {
            lock.unlock();
        }
    }

    @Transactional
    public void forceReassign(Long taskId, String forceRobotCode) {
        lock.lock();
        try {
            Task task = taskRepository.findById(taskId)
                    .orElseThrow(() -> new com.agv.common.ApiException("任务不存在: " + taskId));
            if (task.getStatus() != TaskStatus.ASSIGNED && task.getStatus() != TaskStatus.EXECUTING
                    && task.getStatus() != TaskStatus.EXCEPTION) {
                throw new com.agv.common.ApiException("仅已分配/执行中/异常状态的任务可强制重分配");
            }
            if (task.getType() == TaskType.CHARGING) {
                throw new com.agv.common.ApiException("充电任务绑定专属车辆，不支持强制重分配（可取消后重新触发回充）");
            }
            Long oldRobotId = task.getRobotId();
            reservations.releaseTask(taskId);
            if (oldRobotId != null) {
                robotRepository.findById(oldRobotId).ifPresent(r -> {
                    mqtt.send(Topics.cmd(r.getCode()),
                            "{\"type\":\"CANCEL\",\"taskId\":" + taskId + "}");
                    if (Objects.equals(r.getCurrentTaskId(), taskId) && r.getStatus() != RobotStatus.FAULT) {
                        r.setStatus(RobotStatus.IDLE);
                        r.setCurrentTaskId(null);
                        robotRepository.save(r);
                    }
                });
            }
            task.setStatus(TaskStatus.PENDING);
            task.setRobotId(null);
            task.setPhase(null);
            task.setPlannedPath(List.of());
            task.setRemark("人工强制重分配");
            taskRepository.save(task);
            queue.enqueue(task);
            recordEvent(taskId, "REASSIGNED", null, "人工强制重分配");
            broadcaster.event("REASSIGN", "WARN", null, taskId, "任务被强制重新分配");

            if (forceRobotCode != null) {
                Task reload = taskRepository.findById(taskId).orElseThrow();
                dispatchOne(reload, forceRobotCode);
            }
        } finally {
            lock.unlock();
        }
    }

    // ==================== 受阻重规划 ====================

    private void retryPendingReplans() {
        if (pendingReplans.isEmpty()) {
            return;
        }
        for (Map.Entry<String, Long> e : new ArrayList<>(pendingReplans.entrySet())) {
            Task task = taskRepository.findById(e.getValue()).orElse(null);
            Robot robot = robotRepository.findByCode(e.getKey()).orElse(null);
            if (task == null || robot == null
                    || (task.getStatus() != TaskStatus.ASSIGNED && task.getStatus() != TaskStatus.EXECUTING)
                    || !Objects.equals(robot.getCurrentTaskId(), task.getId())) {
                pendingReplans.remove(e.getKey());
                continue;
            }
            if (attemptReplan(robot, task, "edge blocked")) {
                pendingReplans.remove(e.getKey());
            }
        }
    }

    /** 地图边阻塞后，为所有经过该边的在途任务重规划 */
    public void replanTasksUsingEdge(String from, String to) {
        lock.lock();
        try {
            String edgeKey = topology.edgeKey(from, to);
            for (Task t : taskRepository.findByStatusIn(
                    List.of(TaskStatus.ASSIGNED, TaskStatus.EXECUTING))) {
                List<String> path = t.getPlannedPath();
                if (path == null) continue;
                for (int i = 0; i < path.size() - 1; i++) {
                    if (topology.edgeKey(path.get(i), path.get(i + 1)).equals(edgeKey)) {
                        Robot r = t.getRobotId() == null ? null
                                : robotRepository.findById(t.getRobotId()).orElse(null);
                        if (r != null && !attemptReplan(r, t, "edge " + from + "-" + to + " 阻塞")) {
                            pendingReplans.put(r.getCode(), t.getId());
                        }
                        break;
                    }
                }
            }
        } finally {
            lock.unlock();
        }
    }

    @Transactional
    public boolean attemptReplan(Robot robot, Task task, String reason) {
        RobotTelemetry t = telemetry.get(robot.getCode());
        String pos = t != null && t.node() != null ? t.node() : robot.getCurrentNode();
        if (pos == null) return false;

        boolean loaded = t != null && t.loaded();
        boolean isCharging = task.getType() == TaskType.CHARGING;
        List<String> leg1;
        List<String> leg2;
        if (isCharging) {
            // 充电任务无取货语义：从当前位置直接到充电桩
            leg1 = planner.plan(pos, task.getToNode());
            leg2 = List.of(task.getToNode());
        } else if (loaded) {
            leg1 = List.of(pos);
            leg2 = planner.plan(pos, task.getToNode());
        } else {
            leg1 = planner.plan(pos, task.getFromNode());
            leg2 = planner.plan(task.getFromNode(), task.getToNode());
        }
        if (leg1.isEmpty() || leg2.isEmpty()) {
            return false; // 绕行路径暂不可得，机器人原地等待，下个 tick 再试
        }
        int endDwell = isCharging ? props.getChargeDwellSeconds() : props.getDropDwellSeconds();
        long now = Instant.now().getEpochSecond();
        int chosenDelay = -1;
        List<ReservationStore.Window> chosen = null;
        for (int delay = 0; delay <= MAX_START_DELAY_SEC; delay += DELAY_STEP_SEC) {
            // 等待期间持续预约当前节点（robot 停在 pos），delay 后再发车
            List<ReservationStore.Window> windows =
                    timelineBuilder.build(leg1, leg2, now, delay, null, 0,
                            !loaded && !isCharging, endDwell);
            // 先忽略自身旧窗口检查，避免过早释放导致别人抢入
            if (reservations.freeWindowsIgnoringTasks(List.of(task.getId()), windows)) {
                chosen = windows;
                chosenDelay = delay;
                break;
            }
        }
        if (chosen == null) {
            return false; // 未来窗口均冲突，等待下 tick
        }
        reservations.releaseTask(task.getId());
        if (!reservations.reserveWindows(robot.getCode(), task.getId(), chosen)) {
            return false; // 极小概率竞态：等待期间被他人抢入，下 tick 重试
        }
        List<String> fullPath = joinPaths(leg1, leg2);
        task.setPlannedPath(fullPath);
        taskRepository.save(task);
        recordEvent(task.getId(), "REROUTED", robot.getCode(),
                "因[" + reason + "]延迟 " + chosenDelay + "s 重规划: " + fullPath);
        broadcaster.event("REROUTED", "WARN", robot.getCode(), task.getId(), "路径冲突已重新规划");
        Map<String, Object> cmd = new LinkedHashMap<>();
        cmd.put("type", "PATH_UPDATE");
        cmd.put("taskId", task.getId());
        cmd.put("path", fullPath);
        cmd.put("startDelay", chosenDelay);
        mqtt.send(Topics.cmd(robot.getCode()), toJson(cmd));
        return true;
    }

    // ==================== 状态上报 ====================

    /** 处理 agv/{code}/status（retained，周期上报） */
    @Transactional
    public void handleStatus(String code, Map<String, Object> data) {
        lock.lock();
        try {
            Robot robot = robotRepository.findByCode(code).orElse(null);
            if (robot == null) {
                return;
            }
            Instant now = Instant.now();
            robot.setLastHeartbeat(now);
            if (data.get("battery") != null) {
                robot.setBattery((int) Math.round(doubleVal(data.get("battery"))));
            }
            String node = str(data.get("node"));
            if (node != null) {
                robot.setCurrentNode(node);
            }
            String rStatus = str(data.get("status"));
            if (rStatus != null) {
                switch (rStatus) {
                    case "IDLE" -> {
                        robot.setStatus(RobotStatus.IDLE);
                        // 抢占排空完成：清掉残留任务指针
                        if (robot.getCurrentTaskId() != null) {
                            Long tid = robot.getCurrentTaskId();
                            taskRepository.findById(tid).ifPresent(task -> {
                                if (task.getStatus() == TaskStatus.PENDING) {
                                    robot.setCurrentTaskId(null);
                                }
                            });
                        }
                    }
                    case "BUSY" -> robot.setStatus(RobotStatus.BUSY);
                    case "CHARGING" -> robot.setStatus(RobotStatus.CHARGING);
                    case "OFFLINE" -> {
                        robot.setStatus(RobotStatus.OFFLINE);
                        telemetry.remove(code);
                    }
                    case "FAULT" -> {
                        if (robot.getStatus() != RobotStatus.FAULT) {
                            handleFault(robot, str(data.get("reason")));
                            return;
                        }
                    }
                    default -> { /* OFFLINE 等 */ }
                }
            }
            boolean offline = "OFFLINE".equals(rStatus);
            robotRepository.save(robot);

            if (!offline) {
                String nextNode = str(data.get("nextNode"));
                double[] sh = resolveSpeedHeading(node, nextNode,
                        doubleVal(data.get("progress")), str(data.get("phase")), data);
                telemetry.update(code, new RobotTelemetry(
                        node,
                        nextNode,
                        doubleVal(data.get("progress")),
                        str(data.get("phase")),
                        Boolean.TRUE.equals(data.get("loaded")),
                        (int) Math.round(doubleVal(data.get("pathIndex"))),
                        sh[0],
                        sh[1],
                        now));
            }
        } finally {
            lock.unlock();
        }
    }

    // ==================== 杂项 ====================

    private List<String> joinPaths(List<String> leg1, List<String> leg2) {
        List<String> full = new ArrayList<>(leg1);
        for (int i = 1; i < leg2.size(); i++) {
            full.add(leg2.get(i));
        }
        return full;
    }

    private void recordEvent(Long taskId, String event, String robotCode, String detail) {
        TaskEvent e = new TaskEvent();
        e.setTaskId(taskId);
        e.setEvent(event);
        e.setRobotCode(robotCode);
        e.setDetail(detail);
        eventRepository.save(e);
    }

    private String positionOf(Robot robot) {
        RobotTelemetry t = telemetry.get(robot.getCode());
        if (t != null && t.node() != null) {
            return t.node();
        }
        return robot.getCurrentNode();
    }

    /**
     * 解析速度与航向：优先采用 AGV 自报值，否则由拓扑坐标与边耗时估算。
     * 速度单位为地图单位/秒；航向 0=东、90=南、±180=西（屏幕坐标 y 向下）。
     * @return [speed, headingDegrees]
     */
    private double[] resolveSpeedHeading(String node, String nextNode, double progress,
                                         String phase, Map<String, Object> data) {
        double speed = 0;
        double heading = 0;
        if (data.get("speed") != null) {
            speed = Math.max(0, doubleVal(data.get("speed")));
        }
        if (data.get("heading") != null) {
            heading = doubleVal(data.get("heading"));
        } else if (nextNode != null && node != null) {
            var a = topology.node(node).orElse(null);
            var b = topology.node(nextNode).orElse(null);
            if (a != null && b != null) {
                heading = Math.toDegrees(Math.atan2(
                        b.getY() - a.getY(), b.getX() - a.getX()));
                if (data.get("speed") == null && progress > 0) {
                    double len = Math.hypot(b.getX() - a.getX(), b.getY() - a.getY());
                    speed = len / Math.max(1, props.getStepSeconds());
                }
            }
        }
        if ("CHARGING".equals(phase) || "AT_PICKUP".equals(phase)
                || "AT_DELIVERY".equals(phase) || nextNode == null) {
            speed = 0;
        }
        return new double[]{speed, heading};
    }

    private String toJson(Object o) {
        try {
            return om.writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static Long longVal(Object o) {
        if (o == null) return null;
        if (o instanceof Number n) return n.longValue();
        return Long.parseLong(String.valueOf(o));
    }

    private static double doubleVal(Object o) {
        if (o == null) return 0;
        if (o instanceof Number n) return n.doubleValue();
        return Double.parseDouble(String.valueOf(o));
    }
}
