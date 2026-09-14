package com.agv.scheduler;

import com.agv.domain.entity.Robot;
import com.agv.domain.entity.Task;
import com.agv.domain.enums.RobotStatus;
import com.agv.domain.enums.TaskPhase;
import com.agv.domain.enums.TaskStatus;
import com.agv.domain.enums.TaskType;
import com.agv.domain.repository.RobotRepository;
import com.agv.domain.repository.TaskRepository;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 机器人选择策略：
 * 1) 心跳新鲜的 IDLE 机器人，按 当前位置→取货点 的 A* 代价就近选择；
 * 2) 若无空闲且新任务优先级更高，可"抢占"满足安全条件的忙碌机器人：
 *    车辆未载货（仍在 GOING_PICKUP 阶段）—— 载货任务不可抢占，必须送达。
 * 被抢占机器人进入 PREEMPTING 排空期，期间不再候选；上报 IDLE 后下一 tick 正式接新任务。
 */
@Component
public class RobotSelector {

    private final RobotRepository robotRepository;
    private final TaskRepository taskRepository;
    private final AStarPlanner planner;
    private final TelemetryStore telemetry;
    private final long heartbeatTimeoutMs;

    public RobotSelector(RobotRepository robotRepository,
                         TaskRepository taskRepository,
                         AStarPlanner planner,
                         TelemetryStore telemetry,
                         SchedulerProperties props) {
        this.robotRepository = robotRepository;
        this.taskRepository = taskRepository;
        this.planner = planner;
        this.telemetry = telemetry;
        this.heartbeatTimeoutMs = props.getHeartbeatTimeoutMs();
    }

    public record Candidate(Robot robot, int cost, boolean preempt, Task victim) {
    }

    /** 为任务选择代价最小的候选；forceRobotCode 非空时仅评估指定机器人 */
    public Candidate select(Task task, String forceRobotCode) {
        Instant now = Instant.now();
        List<Candidate> candidates = new ArrayList<>();

        for (Robot robot : robotRepository.findAllByOrderByCodeAsc()) {
            if (forceRobotCode != null && !forceRobotCode.equals(robot.getCode())) {
                continue;
            }
            // 车型能力校验：允许任务类型 + 载重能力（充电任务对所有车型开放、无载重）
            if (!robot.supports(task.getType())) {
                continue;
            }
            if (task.getPayloadWeight() != null && robot.getPayloadCapacity() != null
                    && task.getPayloadWeight() > robot.getPayloadCapacity()) {
                continue;
            }
            // 普通任务不得占用"已绑定待派充电任务"的车辆（该预留给它自己的回充任务）
            if (task.getType() != TaskType.CHARGING
                    && robot.getId() != null
                    && taskRepository.existsByTypeAndRobotIdAndStatusIn(
                            TaskType.CHARGING, robot.getId(), List.of(TaskStatus.PENDING))) {
                continue;
            }
            if (robot.getLastHeartbeat() == null
                    || Duration.between(robot.getLastHeartbeat(), now).toMillis() > heartbeatTimeoutMs) {
                continue; // 心跳超时，不可用（心跳巡检稍后会置 FAULT）
            }
            String pos = positionOf(robot);
            if (pos == null) {
                continue;
            }
            int cost = planner.distance(pos, task.getFromNode());
            if (cost == Integer.MAX_VALUE) {
                continue;
            }

            if (robot.getStatus() == RobotStatus.IDLE) {
                candidates.add(new Candidate(robot, cost, false, null));
            } else if (robot.getStatus() == RobotStatus.BUSY
                    && forceRobotCode == null
                    && canPreempt(robot, task)) {
                Task victim = taskRepository.findById(robot.getCurrentTaskId()).orElse(null);
                if (victim != null) {
                    // 以"即将到达的节点"为起点计算代价（与 preemptStart 保持一致）
                    String start = preemptStart(robot);
                    int preemptCost = planner.distance(start, task.getFromNode());
                    candidates.add(new Candidate(robot,
                            preemptCost == Integer.MAX_VALUE ? cost : preemptCost,
                            true, victim));
                }
            }
        }

        // 空闲优先于抢占；同类按就近代价
        return candidates.stream()
                .min(Comparator.comparing((Candidate c) -> c.preempt())
                        .thenComparingInt(Candidate::cost))
                .orElse(null);
    }

    /** 抢占安全性：高优先级压过低优先级 + 车辆未载货且已驶过当前边中点（即将到下一节点） */
    private boolean canPreempt(Robot robot, Task incoming) {
        if (robot.getCurrentTaskId() == null) {
            return false;
        }
        RobotTelemetry t = telemetry.get(robot.getCode());
        if (t == null || t.loaded() || "PREEMPTING".equals(t.phase())) {
            return false;
        }
        // 边中央被抢占无法瞬时消失：要求已驶过中点，以前方节点为起点接管
        if (!(t.nextNode() != null && t.progress() >= 0.5) && t.nextNode() != null) {
            return false;
        }
        if (incoming.getPriority().getWeight()
                <= taskRepository.findById(robot.getCurrentTaskId())
                .map(v -> v.getPriority().getWeight()).orElse(0)) {
            return false;
        }
        return t.phase() == null || TaskPhase.GOING_PICKUP.name().equals(t.phase());
    }

    /** 抢占场景的规划起点：在边上取前方节点，否则取当前节点 */
    public String preemptStart(Robot robot) {
        RobotTelemetry t = telemetry.get(robot.getCode());
        if (t != null && t.nextNode() != null && t.progress() >= 0.5) {
            return t.nextNode();
        }
        return positionOf(robot);
    }

    private String positionOf(Robot robot) {
        RobotTelemetry t = telemetry.get(robot.getCode());
        if (t != null && t.node() != null) {
            return t.node();
        }
        return robot.getCurrentNode();
    }
}
