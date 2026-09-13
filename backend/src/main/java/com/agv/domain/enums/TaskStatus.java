package com.agv.domain.enums;

/**
 * 任务状态机：
 * PENDING 待分配 → ASSIGNED 已分配 → EXECUTING 执行中 → COMPLETED 完成
 *                ↘ CANCELLED 取消（任意阶段）
 *                ↘ EXCEPTION 异常（AGV 故障 / 心跳超时，任务回到 PENDING 重新参与分配）
 */
public enum TaskStatus {
    PENDING("待分配"),
    ASSIGNED("已分配"),
    EXECUTING("执行中"),
    COMPLETED("完成"),
    CANCELLED("取消"),
    EXCEPTION("异常");

    private final String label;

    TaskStatus(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
