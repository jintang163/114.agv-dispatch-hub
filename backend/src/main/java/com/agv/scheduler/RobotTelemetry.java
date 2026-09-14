package com.agv.scheduler;

import com.agv.domain.entity.Robot;

import java.time.Instant;

/**
 * AGV 实时遥测（MQTT status 上报，仅内存保存，不入库）。
 *
 * @param speed   行驶速度（地图单位/秒，边上插值速度；静止为 0）
 * @param heading 航向角（度，0=东 90=南，由 node→nextNode 坐标推算）
 */
public record RobotTelemetry(
        String node,
        String nextNode,
        /** 0~1，node -> nextNode 边上的行驶进度 */
        double progress,
        String phase,
        boolean loaded,
        int pathIndex,
        double speed,
        double heading,
        Instant updatedAt
) {
    public static RobotTelemetry absent(Robot r) {
        return new RobotTelemetry(r.getCurrentNode(), null, 0, null, false, 0,
                0, 0,
                r.getLastHeartbeat() == null ? Instant.EPOCH : r.getLastHeartbeat());
    }
}
