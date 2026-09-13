package com.agv.domain.enums;

/** AGV 状态 */
public enum RobotStatus {
    OFFLINE("离线"),
    IDLE("空闲"),
    BUSY("忙碌"),
    FAULT("故障");

    private final String label;

    RobotStatus(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
