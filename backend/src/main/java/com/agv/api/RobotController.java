package com.agv.api;

import com.agv.common.ApiException;
import com.agv.domain.entity.Robot;
import com.agv.domain.enums.RobotStatus;
import com.agv.domain.repository.RobotRepository;
import com.agv.infra.mqtt.MqttGateway;
import com.agv.infra.mqtt.Topics;
import com.agv.scheduler.DispatchEngine;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** 机器人监控与管控 */
@RestController
@RequestMapping("/api/robots")
public class RobotController {

    private final RobotRepository robotRepository;
    private final MqttGateway mqtt;
    private final DispatchEngine engine;

    public RobotController(RobotRepository robotRepository, MqttGateway mqtt, DispatchEngine engine) {
        this.robotRepository = robotRepository;
        this.mqtt = mqtt;
        this.engine = engine;
    }

    @GetMapping
    public List<Robot> list() {
        return robotRepository.findAllByOrderByCodeAsc();
    }

    @GetMapping("/{code}")
    public Robot detail(@PathVariable String code) {
        return robotRepository.findByCode(code)
                .orElseThrow(() -> new ApiException("机器人不存在: " + code));
    }

    /** 模拟故障注入：发 FAULT 指令给模拟器（真机由设备自身上报） */
    @PostMapping("/{code}/fault")
    public Map<String, Object> fault(@PathVariable String code,
                                     @RequestBody(required = false) Map<String, String> body) {
        Robot r = mustExist(code);
        String reason = body == null ? "人工注入故障" : body.getOrDefault("reason", "人工注入故障");
        mqtt.send(Topics.cmd(code), "{\"type\":\"FAULT\",\"reason\":\"" + reason + "\"}");
        // 模拟器若不在线，心跳巡检也会兜底；此处直接触发一次故障处理便于演示
        engine.handleRobotEvent(code, "FAULT", Map.of("reason", reason));
        return Map.of("success", true, "robot", r.getCode());
    }

    /** 故障恢复 */
    @PostMapping("/{code}/recover")
    public Map<String, Object> recover(@PathVariable String code) {
        Robot r = mustExist(code);
        mqtt.send(Topics.cmd(code), "{\"type\":\"RESET\"}");
        engine.recover(r);
        return Map.of("success", true, "robot", r.getCode());
    }

    private Robot mustExist(String code) {
        return robotRepository.findByCode(code)
                .orElseThrow(() -> new ApiException("机器人不存在: " + code));
    }
}
