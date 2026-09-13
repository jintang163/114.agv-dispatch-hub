package com.agv.api;

import com.agv.domain.entity.Task;
import com.agv.domain.entity.TaskEvent;
import com.agv.domain.enums.RobotStatus;
import com.agv.domain.enums.TaskStatus;
import com.agv.domain.repository.RobotRepository;
import com.agv.domain.repository.TaskEventRepository;
import com.agv.domain.repository.TaskRepository;
import com.agv.scheduler.PriorityTaskQueue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

/** 仪表盘统计 */
@RestController
@RequestMapping("/api/stats")
public class StatsController {

    private final TaskRepository taskRepository;
    private final RobotRepository robotRepository;
    private final TaskEventRepository eventRepository;
    private final PriorityTaskQueue queue;

    public StatsController(TaskRepository taskRepository,
                           RobotRepository robotRepository,
                           TaskEventRepository eventRepository,
                           PriorityTaskQueue queue) {
        this.taskRepository = taskRepository;
        this.robotRepository = robotRepository;
        this.eventRepository = eventRepository;
        this.queue = queue;
    }

    @GetMapping("/overview")
    public Map<String, Object> overview() {
        Map<String, Object> data = new LinkedHashMap<>();
        Map<String, Long> taskByStatus = new LinkedHashMap<>();
        for (TaskStatus s : TaskStatus.values()) {
            taskByStatus.put(s.name(), taskRepository.countByStatus(s));
        }
        data.put("tasksByStatus", taskByStatus);
        data.put("queueSize", queue.size());

        Map<String, Long> robotByStatus = new LinkedHashMap<>();
        for (RobotStatus s : RobotStatus.values()) {
            robotByStatus.put(s.name(), (long) robotRepository.findByStatus(s).size());
        }
        data.put("robotsByStatus", robotByStatus);

        // 已完成任务平均耗时（分钟）
        List<Task> done = taskRepository.findByStatusIn(List.of(TaskStatus.COMPLETED));
        double avgMinutes = done.stream()
                .filter(t -> t.getStartedAt() != null && t.getCompletedAt() != null)
                .mapToLong(t -> Duration.between(t.getStartedAt(), t.getCompletedAt()).toMinutes())
                .average().orElse(0);
        data.put("avgDurationMinutes", Math.round(avgMinutes * 10) / 10.0);
        data.put("completedTotal", done.size());
        return data;
    }

    /** 近 24 小时（按小时分桶）完成量趋势 */
    @GetMapping("/completion-trend")
    public List<Map<String, Object>> trend() {
        Instant since = Instant.now().minus(java.time.Duration.ofHours(23))
                .truncatedTo(java.time.temporal.ChronoUnit.HOURS);
        Map<String, Long> buckets = new TreeMap<>();
        for (int i = 0; i < 24; i++) {
            buckets.put(since.plus(java.time.Duration.ofHours(i)).toString().substring(0, 13), 0L);
        }
        for (Task t : taskRepository.findByStatusIn(List.of(TaskStatus.COMPLETED))) {
            if (t.getCompletedAt() != null && !t.getCompletedAt().isBefore(since)) {
                String key = t.getCompletedAt().toString().substring(0, 13);
                buckets.computeIfPresent(key, (k, v) -> v + 1);
            }
        }
        List<Map<String, Object>> result = new ArrayList<>();
        buckets.forEach((hour, count) -> result.add(Map.of("hour", hour + ":00", "count", count)));
        return result;
    }

    @GetMapping("/recent-events")
    public List<TaskEvent> recentEvents() {
        return eventRepository.findTop100ByOrderByOccurredAtDesc();
    }
}
