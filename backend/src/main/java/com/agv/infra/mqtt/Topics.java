package com.agv.infra.mqtt;

/** MQTT 主题约定 */
public final class Topics {
    private Topics() {}

    /** 下行：任务派发 agv/{code}/task */
    public static String task(String code) {
        return "agv/" + code + "/task";
    }

    /** 下行：控制指令 agv/{code}/cmd（CANCEL/PREEMPT/HOLD/RESUME/PATH_UPDATE/FAULT/RESET） */
    public static String cmd(String code) {
        return "agv/" + code + "/cmd";
    }

    /** 上行：AGV 周期状态 agv/{code}/status（retained） */
    public static String status(String code) {
        return "agv/" + code + "/status";
    }

    /** 上行：任务/设备事件 agv/{code}/event */
    public static String event(String code) {
        return "agv/" + code + "/event";
    }

    public static final String STATUS_WILDCARD = "agv/+/status";
    public static final String EVENT_WILDCARD = "agv/+/event";

    /** 广播：全车队实时快照（retained，前端直连订阅） */
    public static final String FLEET_STATE = "fleet/state";
    /** 广播：调度事件流 */
    public static final String FLEET_EVENTS = "fleet/events";
}
