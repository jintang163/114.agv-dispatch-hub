package com.agv.domain.enums;

/** 地图节点类型 */
public enum NodeType {
    JUNCTION("路口"),
    PICK("取货点"),
    STORAGE("货架位"),
    DROP("卸货点");

    private final String label;

    NodeType(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
