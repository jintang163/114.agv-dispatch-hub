package com.agv.api.dto;

import com.agv.domain.enums.TaskPriority;
import jakarta.validation.constraints.NotNull;

public record PriorityUpdateRequest(@NotNull TaskPriority priority) {
}
