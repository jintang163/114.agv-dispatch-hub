package com.agv.api;

import com.agv.api.dto.PriorityUpdateRequest;
import com.agv.api.dto.QueueItem;
import com.agv.api.dto.ReassignRequest;
import com.agv.api.dto.TaskCreateRequest;
import com.agv.domain.entity.Task;
import com.agv.domain.entity.TaskEvent;
import com.agv.domain.enums.TaskStatus;
import com.agv.service.TaskService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** WMS / 前端 任务接口 */
@RestController
@RequestMapping("/api/tasks")
public class TaskController {

    private final TaskService taskService;

    public TaskController(TaskService taskService) {
        this.taskService = taskService;
    }

    /** WMS 下发搬运/拣选任务 */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Task create(@Valid @RequestBody TaskCreateRequest request) {
        return taskService.create(request);
    }

    /** 任务列表（可按状态过滤） */
    @GetMapping
    public List<Task> list(@RequestParam(required = false) TaskStatus status) {
        return taskService.list(status);
    }

    @GetMapping("/{id}")
    public Task detail(@PathVariable Long id) {
        return taskService.detail(id);
    }

    @GetMapping("/{id}/events")
    public List<TaskEvent> events(@PathVariable Long id) {
        return taskService.events(id);
    }

    /** 动态调整优先级（高优插队） */
    @PutMapping("/{id}/priority")
    public Task priority(@PathVariable Long id, @Valid @RequestBody PriorityUpdateRequest request) {
        return taskService.updatePriority(id, request.priority());
    }

    /** 取消任务 */
    @PostMapping("/{id}/cancel")
    public ResponseEntity<Map<String, Object>> cancel(@PathVariable Long id) {
        taskService.cancel(id);
        return ResponseEntity.ok(Map.of("success", true, "id", id));
    }

    /** 强制重分配（AGV 故障 / 路径阻塞），body 可指定 robotCode */
    @PostMapping("/{id}/reassign")
    public ResponseEntity<Map<String, Object>> reassign(@PathVariable Long id,
                                                        @RequestBody(required = false) ReassignRequest request) {
        taskService.reassign(id, request == null ? null : request.robotCode());
        return ResponseEntity.ok(Map.of("success", true, "id", id));
    }

    /** 实时调度队列（按 紧急度 + 截止时间 + 老化 排序） */
    @GetMapping("/queue/view")
    public QueueItem.QueueResponse queue() {
        return new QueueItem.QueueResponse(taskService.queueView());
    }
}
