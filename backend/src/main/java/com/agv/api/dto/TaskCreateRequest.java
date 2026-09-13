package com.agv.api.dto;

import com.agv.domain.enums.TaskPriority;
import com.agv.domain.enums.TaskType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

/** WMS 下发任务请求 */
public record TaskCreateRequest(
        String externalNo,
        @NotNull TaskType type,
        TaskPriority priority,
        @NotBlank String fromNode,
        @NotBlank String toNode,
        /** ISO-8601 期望完成时间，可空 */
        Instant deadline,
        String remark
) {
}
