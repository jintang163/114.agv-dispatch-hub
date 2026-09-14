package com.agv.api.dto;

import com.agv.domain.enums.TaskPriority;
import com.agv.domain.enums.TaskType;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
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
        /** 货物重量 kg，派车时校验 AGV 载重能力，可空 */
        @Min(0) @Max(99999) Integer payloadWeight,
        String remark
) {
}
