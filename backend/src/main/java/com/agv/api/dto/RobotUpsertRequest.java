package com.agv.api.dto;

import com.agv.domain.enums.TaskType;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/** AGV 注册 / 维护请求 */
public record RobotUpsertRequest(
        @NotBlank @Size(max = 32) String code,
        @Size(max = 64) String name,
        @Size(max = 48) String model,
        /** 额定载重能力 kg（0-99999） */
        @Min(0) @Max(99999) Integer payloadCapacity,
        /** 允许的任务类型；空集合表示全部允许（CHARGING 始终隐式允许） */
        List<TaskType> allowedTaskTypes,
        /** 归属充电桩节点 code，可空 */
        String homeCharger,
        /** 初始所在节点（注册时使用，可空） */
        String currentNode,
        /** 初始电量 0-100 */
        @Min(0) @Max(100) Integer battery
) {
}
