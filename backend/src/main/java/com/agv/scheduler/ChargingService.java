package com.agv.scheduler;

import com.agv.domain.entity.MapNode;
import com.agv.domain.entity.Robot;
import com.agv.domain.entity.Task;
import com.agv.domain.entity.TaskEvent;
import com.agv.domain.enums.NodeType;
import com.agv.domain.enums.TaskPriority;
import com.agv.domain.enums.TaskStatus;
import com.agv.domain.enums.TaskType;
import com.agv.domain.repository.MapNodeRepository;
import com.agv.domain.repository.TaskEventRepository;
import com.agv.domain.repository.TaskRepository;
import com.agv.infra.mqtt.FleetBroadcaster;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 电量管理 / 自动回充：
 *  - 判定低电（IDLE 低于阈值）与严重低电（执行中，完成后立即回充）
 *  - 幂等生成 CHARGING 任务：归属桩优先，否则就近选空闲充电桩（已被其他活动充电任务占用的桩跳过）
 *  - 充电任务进入统一优先级队列，由 DispatchEngine 正常派车、A* 寻路与时间窗预约
 */
@Service
public class ChargingService {

    /** 视为"占用充电桩"的充电任务状态（待派/在途/充电中） */
    private static final List<TaskStatus> ACTIVE = List.of(
            TaskStatus.PENDING, TaskStatus.ASSIGNED, TaskStatus.EXECUTING);

    private final MapNodeRepository nodeRepository;
    private final TaskRepository taskRepository;
    private final TaskEventRepository eventRepository;
    private final AStarPlanner planner;
    private final PriorityTaskQueue queue;
    private final TopologyCache topology;
    private final FleetBroadcaster broadcaster;
    private final SchedulerProperties props;

    public ChargingService(MapNodeRepository nodeRepository,
                           TaskRepository taskRepository,
                           TaskEventRepository eventRepository,
                           AStarPlanner planner,
                           PriorityTaskQueue queue,
                           TopologyCache topology,
                           FleetBroadcaster broadcaster,
                           SchedulerProperties props) {
        this.nodeRepository = nodeRepository;
        this.taskRepository = taskRepository;
        this.eventRepository = eventRepository;
        this.planner = planner;
        this.queue = queue;
        this.topology = topology;
        this.broadcaster = broadcaster;
        this.props = props;
    }

    /** 空闲车低电：应立即回充 */
    public boolean shouldAutoCharge(Robot r) {
        return r.getStatus() == com.agv.domain.enums.RobotStatus.IDLE
                && r.getBattery() != null
                && r.getBattery() < props.getLowBatteryThreshold();
    }

    /** 执行中车辆严重低电：不打断当前任务，但完成后立即回充 */
    public boolean isCritical(Robot r) {
        return r.getBattery() != null && r.getBattery() < props.getCriticalBatteryThreshold();
    }

    /** 是否已有活动充电任务（待派/在途/充电中），避免重复生成 */
    public boolean hasActiveChargeTask(Robot r) {
        if (r.getId() == null) {
            return false;
        }
        return taskRepository.existsByTypeAndRobotIdAndStatusIn(TaskType.CHARGING, r.getId(), ACTIVE);
    }

    /**
     * 为机器人生成充电任务（幂等）。
     * @param reason 触发原因，写入事件/备注
     * @return 新建或已存在的充电任务；无可用充电桩返回 null
     */
    @Transactional
    public Task requestCharge(Robot robot, String reason) {
        if (hasActiveChargeTask(robot)) {
            return taskRepository.findByRobotIdAndStatusIn(robot.getId(), ACTIVE).stream()
                    .filter(t -> t.getType() == TaskType.CHARGING)
                    .findFirst().orElse(null);
        }
        String pos = robot.getCurrentNode();
        String charger = chooseCharger(robot, pos);
        if (charger == null) {
            return null;
        }

        Task task = new Task();
        task.setType(TaskType.CHARGING);
        task.setPriority(TaskPriority.MEDIUM);
        task.setStatus(TaskStatus.PENDING);
        task.setFromNode(pos);
        task.setToNode(charger);
        // 充电任务为该车专属：绑定 robotId，DispatchEngine 只派给该车，RobotSelector 也会将其"预留"
        task.setRobotId(robot.getId());
        task.setRemark(reason);
        task.setPlannedPath(new ArrayList<>());
        task.setActualPath(new ArrayList<>());
        taskRepository.save(task);
        queue.enqueue(task);

        record(task.getId(), "CREATED", robot.getCode(),
                "自动充电任务：" + pos + " → 充电桩 " + charger + "（" + reason + "）");
        broadcaster.event("CHARGE_START", "INFO", robot.getCode(), task.getId(),
                robot.getCode() + " 电量 " + robot.getBattery() + "%，前往充电桩 " + charger);
        return task;
    }

    /** 归属桩优先（须空闲可达），否则就近选未被占用的充电桩 */
    private String chooseCharger(Robot robot, String pos) {
        List<MapNode> chargers = nodeRepository.findByTypeOrderByCodeAsc(NodeType.CHARGER);
        if (chargers.isEmpty() || pos == null) {
            return null;
        }
        String home = robot.getHomeCharger();
        if (home != null && topology.contains(home)
                && isCharger(home, chargers) && !isOccupied(home)
                && planner.distance(pos, home) != Integer.MAX_VALUE) {
            return home;
        }
        String best = null;
        int bestCost = Integer.MAX_VALUE;
        for (MapNode c : chargers) {
            if (isOccupied(c.getCode())) {
                continue;
            }
            int cost = planner.distance(pos, c.getCode());
            if (cost < bestCost) {
                bestCost = cost;
                best = c.getCode();
            }
        }
        return best;
    }

    private boolean isCharger(String code, List<MapNode> chargers) {
        return chargers.stream().anyMatch(c -> c.getCode().equals(code));
    }

    /** 该充电桩是否已被某个活动充电任务占用（在途或正在充电） */
    public boolean isOccupied(String chargerCode) {
        return taskRepository.existsByTypeAndToNodeAndStatusIn(TaskType.CHARGING, chargerCode, ACTIVE);
    }

    /** "无空闲充电桩" 告警节流：同车 60s 内最多一条，避免巡检刷屏 */
    private final Map<String, Instant> noChargerWarnedAt = new ConcurrentHashMap<>();

    public void notifyNoChargerThrottled(Robot robot) {
        Instant last = noChargerWarnedAt.get(robot.getCode());
        Instant now = Instant.now();
        if (last != null && Duration.between(last, now).toSeconds() < 60) {
            return;
        }
        noChargerWarnedAt.put(robot.getCode(), now);
        broadcaster.event("LOW_BATTERY", "WARN", robot.getCode(), null,
                robot.getCode() + " 低电量 " + robot.getBattery() + "%，暂无空闲充电桩");
    }

    private void record(Long taskId, String event, String robotCode, String detail) {
        TaskEvent e = new TaskEvent();
        e.setTaskId(taskId);
        e.setEvent(event);
        e.setRobotCode(robotCode);
        e.setDetail(detail);
        eventRepository.save(e);
    }
}
