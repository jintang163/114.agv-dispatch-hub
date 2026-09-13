package com.agv.domain.enums;

/** 任务类型：搬运 / 拣选 */
public enum TaskType {
    TRANSPORT("搬运"),
    PICKING("拣选");

    private final String label;

    TaskType(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
