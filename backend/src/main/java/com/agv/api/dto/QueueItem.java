package com.agv.api.dto;

import java.time.Instant;
import java.util.List;

/** 调度队列视图：按 优先级 + 截止时间 排序后的待分配任务 */
public record QueueItem(
        Long taskId,
        String type,
        String priority,
        String fromNode,
        String toNode,
        Instant deadline,
        double score,
        Long agingMinutes
) {
    public record QueueResponse(List<QueueItem> queue) {}
}
