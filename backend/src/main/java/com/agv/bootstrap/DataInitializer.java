package com.agv.bootstrap;

import com.agv.domain.entity.MapEdge;
import com.agv.domain.entity.MapNode;
import com.agv.domain.entity.Robot;
import com.agv.domain.entity.Task;
import com.agv.domain.enums.NodeType;
import com.agv.domain.enums.RobotStatus;
import com.agv.domain.enums.TaskStatus;
import com.agv.domain.repository.MapEdgeRepository;
import com.agv.domain.repository.MapNodeRepository;
import com.agv.domain.repository.RobotRepository;
import com.agv.domain.repository.TaskRepository;
import com.agv.scheduler.PriorityTaskQueue;
import com.agv.scheduler.TopologyCache;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * 初始化 9 列 × 5 行网格仓库地图：
 *   P-01..P-06 取货点（北侧）
 *   J{r}-{c}   路口节点网格
 *   D-01..D-03 卸货点（南侧）
 * 并播种 6 台 AGV；重启后清理残留预约、重建优先级队列。
 */
@Slf4j
@Component
@Order(1)
@RequiredArgsConstructor
public class DataInitializer implements CommandLineRunner {

    private static final int COLS = 9;
    private static final int ROWS = 5;
    private static final int[] PICK_COLS = {1, 2, 3, 5, 6, 7};
    private static final int[] DROP_COLS = {0, 4, 8};
    /** 充电桩：西侧外延，分别接在 0/2/4 行最左路口 */
    private static final int[] CHARGER_ROWS = {0, 2, 4};

    private final MapNodeRepository nodeRepository;
    private final MapEdgeRepository edgeRepository;
    private final RobotRepository robotRepository;
    private final TaskRepository taskRepository;
    private final TopologyCache topologyCache;
    private final PriorityTaskQueue queue;
    private final StringRedisTemplate redis;

    @Override
    @Transactional
    public void run(String... args) {
        boolean emptyMap = nodeRepository.count() == 0;
        if (emptyMap) {
            seedMap();
        } else {
            // 升级场景：为既有地图幂等补播种充电桩
            seedChargersIfMissing();
        }
        topologyCache.refresh();

        if (robotRepository.count() == 0) {
            seedRobots();
        }

        // 重启恢复：清理上次运行残留的预约锁，重建待分配队列
        cleanupRedisPrefix("res:");
        redis.delete(PriorityTaskQueue.QUEUE_KEY);
        List<Task> pending = taskRepository.findByStatusOrderByCreatedAtAsc(TaskStatus.PENDING);
        pending.forEach(queue::enqueue);
        log.info("调度中心启动完成：地图节点 {}，边 {}，AGV {}，待分配任务 {}",
                nodeRepository.count(), edgeRepository.count(), robotRepository.count(), pending.size());
    }

    private void seedMap() {
        // 路口网格
        for (int r = 0; r < ROWS; r++) {
            for (int c = 0; c < COLS; c++) {
                saveNode(junction(r, c), 120 + c * 100, 150 + r * 100,
                        NodeType.JUNCTION, "路口 " + r + "-" + c);
            }
        }
        // 取货点（北侧伸出边）
        for (int i = 0; i < PICK_COLS.length; i++) {
            int c = PICK_COLS[i];
            String code = String.format("P-%02d", i + 1);
            saveNode(code, 120 + c * 100, 50, NodeType.PICK, "取货点 " + (i + 1));
            saveEdge(code, junction(0, c));
        }
        // 卸货点（南侧伸出边）
        for (int i = 0; i < DROP_COLS.length; i++) {
            int c = DROP_COLS[i];
            String code = String.format("D-%02d", i + 1);
            saveNode(code, 120 + c * 100, 670, NodeType.DROP, "卸货点 " + (i + 1));
            saveEdge(junction(ROWS - 1, c), code);
        }
        // 充电桩（西侧伸出边）
        seedChargers();
        // 网格横向边
        for (int r = 0; r < ROWS; r++) {
            for (int c = 0; c < COLS - 1; c++) {
                saveEdge(junction(r, c), junction(r, c + 1));
            }
        }
        // 网格纵向边
        for (int r = 0; r < ROWS - 1; r++) {
            for (int c = 0; c < COLS; c++) {
                saveEdge(junction(r, c), junction(r + 1, c));
            }
        }
    }

    private void seedChargersIfMissing() {
        if (nodeRepository.findByType(NodeType.CHARGER).isEmpty()) {
            seedChargers();
        }
    }

    private void seedChargers() {
        for (int i = 0; i < CHARGER_ROWS.length; i++) {
            int r = CHARGER_ROWS[i];
            String code = String.format("C-%02d", i + 1);
            if (nodeRepository.findByCode(code).isEmpty()) {
                saveNode(code, 45, 150 + r * 100, NodeType.CHARGER, "充电桩 " + (i + 1));
            }
            saveEdge(junction(r, 0), code);
        }
    }

    private void seedRobots() {
        String[] anchors = {junction(4, 0), junction(4, 2), junction(4, 4),
                junction(4, 6), junction(4, 8), junction(2, 8)};
        // 型号 / 载重 / 初始电量（刻意分散以便演示低电自动回充）
        String[] models = {"FL-500", "FL-500", "FL-1000", "FL-500", "FL-1000", "FL-500"};
        int[] capacities = {500, 500, 1000, 500, 1000, 500};
        int[] batteries = {98, 30, 20, 88, 45, 12};
        String[] homeChargers = {"C-03", "C-02", "C-02", "C-03", "C-02", "C-01"};
        for (int i = 0; i < anchors.length; i++) {
            Robot robot = new Robot();
            robot.setCode(String.format("AGV-%02d", i + 1));
            robot.setName("叉车机器人 " + (i + 1) + "号");
            robot.setModel(models[i]);
            robot.setPayloadCapacity(capacities[i]);
            // 空列表 = 全部任务类型允许（CHARGING 始终隐式允许）
            robot.setAllowedTaskTypes(new java.util.ArrayList<>());
            robot.setHomeCharger(homeChargers[i]);
            robot.setStatus(RobotStatus.OFFLINE);
            robot.setCurrentNode(anchors[i]);
            robot.setBattery(batteries[i]);
            robot.setLastHeartbeat(Instant.EPOCH);
            robotRepository.save(robot);
        }
    }

    private String junction(int r, int c) {
        return String.format("J%d-%d", r, c);
    }

    private void saveNode(String code, int x, int y, NodeType type, String name) {
        MapNode n = new MapNode();
        n.setCode(code);
        n.setX(x);
        n.setY(y);
        n.setType(type);
        n.setName(name);
        nodeRepository.save(n);
    }

    private void saveEdge(String from, String to) {
        if (edgeRepository.existsByFromNodeAndToNode(from, to)) {
            return;
        }
        MapEdge e = new MapEdge();
        e.setFromNode(from);
        e.setToNode(to);
        e.setBidirectional(true);
        e.setWeight(1);
        e.setBlocked(false);
        edgeRepository.save(e);
    }

    private void cleanupRedisPrefix(String prefix) {
        try (var scan = redis.scan(org.springframework.data.redis.core.ScanOptions
                .scanOptions().match(prefix + "*").count(500).build())) {
            java.util.List<String> keys = new java.util.ArrayList<>();
            while (scan.hasNext()) {
                keys.add(scan.next());
                if (keys.size() >= 500) {
                    redis.delete(keys);
                    keys.clear();
                }
            }
            if (!keys.isEmpty()) {
                redis.delete(keys);
            }
        }
    }
}
