package com.agv.scheduler;

import com.agv.domain.entity.Robot;
import com.agv.domain.enums.RobotStatus;

import java.time.Instant;

/** AGV 实时遥测（MQTT status 上报，仅内存保存，不入库） */
public record RobotTelemetry(
        String node,
        String nextNode,
        /** 0~1，node -> nextNode 边上的行驶进度 */
        double progress,
        String phase,
        boolean loaded,
        int pathIndex,
        Instant updatedAt
) {
    public static RobotTelemetry absent(Robot r) {
        return new RobotTelemetry(r.getCurrentNode(), null, 0, null, false, 0,
                r.getLastHeartbeat() == null ? Instant.EPOCH : r.getLastHeartbeat());
    }
}
