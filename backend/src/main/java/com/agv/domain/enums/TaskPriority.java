package com.agv.domain.enums;

import com.fasterxml.jackson.annotation.JsonCreator;

/** 紧急程度：HIGH(高) > MEDIUM(中) > LOW(低) */
public enum TaskPriority {
    HIGH(3, "高"),
    MEDIUM(2, "中"),
    LOW(1, "低");

    private final int weight;
    private final String label;

    TaskPriority(int weight, String label) {
        this.weight = weight;
        this.label = label;
    }

    public int getWeight() {
        return weight;
    }

    public String getLabel() {
        return label;
    }

    @JsonCreator
    public static TaskPriority fromValue(String value) {
        if (value == null || value.isBlank()) {
            return MEDIUM;
        }
        String v = value.trim();
        for (TaskPriority p : values()) {
            if (p.name().equalsIgnoreCase(v) || p.label.equals(v)) {
                return p;
            }
        }
        throw new IllegalArgumentException("未知优先级: " + value);
    }
}
