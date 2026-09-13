package com.agv.scheduler;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 机器人实时遥测缓存（含边行驶进度，供地图插值与抢占安全性判断） */
@Component
public class TelemetryStore {

    private final Map<String, RobotTelemetry> data = new ConcurrentHashMap<>();

    public void update(String code, RobotTelemetry t) {
        data.put(code, t);
    }

    public RobotTelemetry get(String code) {
        return data.get(code);
    }

    public Map<String, RobotTelemetry> all() {
        return Map.copyOf(data);
    }

    public void remove(String code) {
        data.remove(code);
    }
}
