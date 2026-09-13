package com.agv.scheduler;

import com.agv.domain.entity.Task;
import com.agv.domain.enums.TaskStatus;
import com.agv.domain.repository.TaskRepository;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 待分配任务优先级队列（Redis ZSET 实现）。
 *
 * score 越大越优先：
 *   score = 紧急度权重 * 1_000_000
 *         + 截止时间紧迫度 * 100        （距截止越近分越高，0~144000）
 *         + 等待时长（分钟，封顶 720）   （防饥饿，但不会跨越紧急度档位）
 *
 * 高优先级任务入队即获得更高 score，ZREVRANGE 取队首 —— 天然"插队"。
 */
@Component
public class PriorityTaskQueue {

    public static final String QUEUE_KEY = "queue:tasks";

    /** 超过该时间（分钟）的截止时间不再增加紧迫度 */
    private static final long DEADLINE_HORIZON_MIN = 24 * 60;
    /** 等待时长加分上限（分钟） */
    private static final long AGING_CAP_MIN = 720;

    private final StringRedisTemplate redis;
    private final TaskRepository taskRepository;

    public PriorityTaskQueue(StringRedisTemplate redis, TaskRepository taskRepository) {
        this.redis = redis;
        this.taskRepository = taskRepository;
    }

    public double score(Task task, Instant now) {
        double urgency = 0;
        if (task.getDeadline() != null) {
            long minutesLeft = Duration.between(now, task.getDeadline()).toMinutes();
            urgency = Math.max(0, DEADLINE_HORIZON_MIN - Math.max(minutesLeft, -DEADLINE_HORIZON_MIN));
        }
        long agingMinutes = Math.min(AGING_CAP_MIN, Duration.between(task.getCreatedAt(), now).toMinutes());
        return task.getPriority().getWeight() * 1_000_000d + urgency * 100d + agingMinutes;
    }

    public void enqueue(Task task) {
        redis.opsForZSet().add(QUEUE_KEY, String.valueOf(task.getId()), score(task, Instant.now()));
    }

    public void remove(Long taskId) {
        redis.opsForZSet().remove(QUEUE_KEY, String.valueOf(taskId));
    }

    /** 队首任务 ID（最紧急），队列空返回 null */
    public Long peekTopId() {
        Set<String> top = redis.opsForZSet().reverseRange(QUEUE_KEY, 0, 0);
        if (top == null || top.isEmpty()) {
            return null;
        }
        return Long.valueOf(top.iterator().next());
    }

    /** 按紧迫度/等待时间动态变化，周期性重算全部 score */
    public void rescoreAll(List<Task> pendingTasks, Instant now) {
        for (Task t : pendingTasks) {
            redis.opsForZSet().add(QUEUE_KEY, String.valueOf(t.getId()), score(t, now));
        }
    }

    public int size() {
        Long z = redis.opsForZSet().zCard(QUEUE_KEY);
        return z == null ? 0 : z.intValue();
    }

    /** 队列快照（队首在前） */
    public List<Long> orderedIds() {
        Set<String> members = redis.opsForZSet().reverseRange(QUEUE_KEY, 0, -1);
        List<Long> ids = new ArrayList<>();
        if (members != null) {
            members.forEach(m -> ids.add(Long.valueOf(m)));
        }
        return ids;
    }
}
