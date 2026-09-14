package com.agv.domain.enums;

/** 任务执行阶段（用于抢占安全性判断：取货前可安全抢占） */
public enum TaskPhase {
    /** 前往起点（取货点）途中 */
    GOING_PICKUP,
    /** 到达起点，正在取货/拣选 */
    AT_PICKUP,
    /** 已取货，前往终点途中（载货，不可抢占） */
    GOING_DELIVERY,
    /** 到达终点，正在卸货 */
    AT_DELIVERY,
    /** 前往充电桩途中（自动充电任务） */
    GOING_CHARGER,
    /** 已到桩，正在充电 */
    CHARGING,
    /** 完成 */
    DONE
}
