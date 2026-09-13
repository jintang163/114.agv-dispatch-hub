package com.agv.infra.mqtt;

import com.agv.domain.entity.Robot;
import com.agv.domain.entity.Task;
import com.agv.domain.enums.TaskStatus;
import com.agv.domain.repository.RobotRepository;
import com.agv.domain.repository.TaskRepository;
import com.agv.scheduler.RobotTelemetry;
import com.agv.scheduler.TelemetryStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;

/**
 * 车队实时快照广播：每 1s 向 fleet/state（retained）发布全量快照，前端直连 EMQX 订阅。
 * 调度事件向 fleet/events 逐条发布（非 retained）。
 */
@Slf4j
@Component
public class FleetBroadcaster {

    private final MqttGateway mqtt;
    private final RobotRepository robotRepository;
    private final TaskRepository taskRepository;
    private final TelemetryStore telemetry;
    private final ObjectMapper om;

    public FleetBroadcaster(MqttGateway mqtt,
                            RobotRepository robotRepository,
                            TaskRepository taskRepository,
                            TelemetryStore telemetry,
                            ObjectMapper om) {
        this.mqtt = mqtt;
        this.robotRepository = robotRepository;
        this.taskRepository = taskRepository;
        this.telemetry = telemetry;
        this.om = om;
    }

    @Scheduled(fixedDelay = 1000, initialDelay = 1500)
    public void broadcastSnapshot() {
        try {
            List<Robot> robots = robotRepository.findAllByOrderByCodeAsc();
            List<Map<String, Object>> robotViews = new ArrayList<>();
            for (Robot r : robots) {
                Map<String, Object> v = new LinkedHashMap<>();
                v.put("code", r.getCode());
                v.put("name", r.getName());
                v.put("status", r.getStatus().name());
                v.put("node", r.getCurrentNode());
                v.put("battery", r.getBattery());
                v.put("taskId", r.getCurrentTaskId());
                RobotTelemetry t = telemetry.get(r.getCode());
                if (t != null) {
                    v.put("nextNode", t.nextNode());
                    v.put("progress", t.progress());
                    v.put("phase", t.phase());
                    v.put("loaded", t.loaded());
                }
                robotViews.add(v);
            }

            Map<String, Object> snapshot = new LinkedHashMap<>();
            snapshot.put("ts", Instant.now().toString());
            snapshot.put("robots", robotViews);
            mqtt.send(Topics.FLEET_STATE, true, om.writeValueAsString(snapshot));
        } catch (Exception e) {
            log.debug("snapshot broadcast failed: {}", e.getMessage());
        }
    }

    public void event(String type, String level, String robotCode, Long taskId, String message) {
        try {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("ts", Instant.now().toString());
            e.put("type", type);
            e.put("level", level == null ? "INFO" : level);
            e.put("robot", robotCode);
            e.put("taskId", taskId);
            e.put("message", message);
            mqtt.send(Topics.FLEET_EVENTS, false, om.writeValueAsString(e));
        } catch (Exception ignore) {
        }
    }
}
