package com.agv.domain.enums;

/** 任务类型：搬运 / 拣选 / 自动充电（系统生成，不由 WMS 直接下发） */
public enum TaskType {
    TRANSPORT("搬运"),
    PICKING("拣选"),
    CHARGING("充电");

    private final String label;

    TaskType(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
