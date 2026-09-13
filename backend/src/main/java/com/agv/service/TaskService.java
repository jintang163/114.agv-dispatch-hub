package com.agv.service;

import com.agv.api.dto.QueueItem;
import com.agv.api.dto.TaskCreateRequest;
import com.agv.common.ApiException;
import com.agv.domain.entity.Task;
import com.agv.domain.entity.TaskEvent;
import com.agv.domain.enums.TaskPriority;
import com.agv.domain.enums.TaskStatus;
import com.agv.domain.repository.TaskEventRepository;
import com.agv.domain.repository.TaskRepository;
import com.agv.infra.mqtt.FleetBroadcaster;
import com.agv.scheduler.DispatchEngine;
import com.agv.scheduler.PriorityTaskQueue;
import com.agv.scheduler.TopologyCache;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** 任务生命周期管理：下发 / 插队调权 / 取消 / 强制重分配 / 队列视图 */
@Service
public class TaskService {

    private final TaskRepository taskRepository;
    private final TaskEventRepository eventRepository;
    private final PriorityTaskQueue queue;
    private final TopologyCache topology;
    private final DispatchEngine engine;
    private final FleetBroadcaster broadcaster;

    public TaskService(TaskRepository taskRepository,
                       TaskEventRepository eventRepository,
                       PriorityTaskQueue queue,
                       TopologyCache topology,
                       DispatchEngine engine,
                       FleetBroadcaster broadcaster) {
        this.taskRepository = taskRepository;
        this.eventRepository = eventRepository;
        this.queue = queue;
        this.topology = topology;
        this.engine = engine;
        this.broadcaster = broadcaster;
    }

    @Transactional
    public Task create(TaskCreateRequest req) {
        if (!topology.contains(req.fromNode())) {
            throw new ApiException("起点不存在: " + req.fromNode());
        }
        if (!topology.contains(req.toNode())) {
            throw new ApiException("终点不存在: " + req.toNode());
        }
        if (req.fromNode().equals(req.toNode())) {
            throw new ApiException("起点与终点不能相同");
        }
        Task task = new Task();
        task.setExternalNo(req.externalNo());
        task.setType(req.type());
        task.setPriority(req.priority() == null ? TaskPriority.MEDIUM : req.priority());
        task.setStatus(TaskStatus.PENDING);
        task.setFromNode(req.fromNode());
        task.setToNode(req.toNode());
        task.setDeadline(req.deadline());
        task.setRemark(req.remark());
        task.setPlannedPath(new ArrayList<>());
        task.setActualPath(new ArrayList<>());
        taskRepository.save(task);

        queue.enqueue(task);
        record(task.getId(), "CREATED", null,
                "WMS 下发：" + task.getType().getLabel() + " " + req.fromNode() + " → " + req.toNode()
                        + "，优先级 " + task.getPriority());
        broadcaster.event("CREATED", "INFO", null, task.getId(),
                "新任务 " + task.getType().getLabel() + "（" + task.getPriority() + "）进入队列");
        return task;
    }

    /** 动态调整优先级 —— 高优任务插队的入口：改权后立即按新 score 重排 ZSET */
    @Transactional
    public Task updatePriority(Long taskId, TaskPriority priority) {
        Task task = taskRepository.findById(taskId)
                .orElseThrow(() -> new ApiException("任务不存在: " + taskId));
        if (task.getStatus() != TaskStatus.PENDING) {
            throw new ApiException("仅待分配任务可调整优先级（已在途任务请使用取消/重分配）");
        }
        task.setPriority(priority);
        taskRepository.save(task);
        queue.enqueue(task); // ZADD 覆盖旧 score，立即插队
        record(taskId, "REPRIORITIZED", null, "优先级调整为 " + priority);
        broadcaster.event("REPRIORITIZED", "INFO", null, taskId, "任务优先级调整为 " + priority);
        return task;
    }

    public void cancel(Long taskId) {
        engine.cancelTask(taskId);
    }

    public void reassign(Long taskId, String robotCode) {
        engine.forceReassign(taskId, robotCode);
    }

    @Transactional(readOnly = true)
    public List<Task> list(TaskStatus status) {
        if (status != null) {
            return taskRepository.findByStatusIn(List.of(status)).stream()
                    .sorted((a, b) -> b.getCreatedAt().compareTo(a.getCreatedAt()))
                    .toList();
        }
        return taskRepository.findAll().stream()
                .sorted((a, b) -> b.getCreatedAt().compareTo(a.getCreatedAt()))
                .toList();
    }

    @Transactional(readOnly = true)
    public Task detail(Long id) {
        return taskRepository.findById(id)
                .orElseThrow(() -> new ApiException("任务不存在: " + id));
    }

    @Transactional(readOnly = true)
    public List<TaskEvent> events(Long taskId) {
        if (!taskRepository.existsById(taskId)) {
            throw new ApiException("任务不存在: " + taskId);
        }
        return eventRepository.findByTaskIdOrderByOccurredAtAsc(taskId);
    }

    /** 优先级队列快照（队首在前） */
    @Transactional(readOnly = true)
    public List<QueueItem> queueView() {
        Instant now = Instant.now();
        List<QueueItem> items = new ArrayList<>();
        for (Long id : queue.orderedIds()) {
            Task t = taskRepository.findById(id).orElse(null);
            if (t == null || t.getStatus() != TaskStatus.PENDING) {
                continue;
            }
            items.add(new QueueItem(
                    t.getId(),
                    t.getType().name(),
                    t.getPriority().name(),
                    t.getFromNode(),
                    t.getToNode(),
                    t.getDeadline(),
                    queue.score(t, now),
                    t.getCreatedAt() == null ? 0
                            : Duration.between(t.getCreatedAt(), now).toMinutes()));
        }
        return items;
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
