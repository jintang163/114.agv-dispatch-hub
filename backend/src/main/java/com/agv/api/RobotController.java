package com.agv.api;

import com.agv.api.dto.RobotUpsertRequest;
import com.agv.common.ApiException;
import com.agv.domain.entity.MapNode;
import com.agv.domain.entity.Robot;
import com.agv.domain.enums.NodeType;
import com.agv.domain.repository.MapNodeRepository;
import com.agv.domain.repository.RobotRepository;
import com.agv.infra.mqtt.MqttGateway;
import com.agv.infra.mqtt.Topics;
import com.agv.scheduler.DispatchEngine;
import com.agv.service.RobotService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** 机器人监控、注册维护与管控 */
@RestController
@RequestMapping("/api/robots")
public class RobotController {

    private final RobotRepository robotRepository;
    private final MapNodeRepository nodeRepository;
    private final RobotService robotService;
    private final MqttGateway mqtt;
    private final DispatchEngine engine;

    public RobotController(RobotRepository robotRepository,
                           MapNodeRepository nodeRepository,
                           RobotService robotService,
                           MqttGateway mqtt,
                           DispatchEngine engine) {
        this.robotRepository = robotRepository;
        this.nodeRepository = nodeRepository;
        this.robotService = robotService;
        this.mqtt = mqtt;
        this.engine = engine;
    }

    @GetMapping
    public List<Robot> list() {
        return robotRepository.findAllByOrderByCodeAsc();
    }

    @GetMapping("/{code}")
    public Robot detail(@PathVariable String code) {
        return mustExist(code);
    }

    /** 充电桩节点列表（含占用状态由前端结合任务判断，这里返回全部桩） */
    @GetMapping("/chargers")
    public List<MapNode> chargers() {
        return nodeRepository.findByTypeOrderByCodeAsc(NodeType.CHARGER);
    }

    /** 注册新 AGV：编号、型号、载重、允许任务类型、归属充电桩 */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Robot register(@Valid @RequestBody RobotUpsertRequest request) {
        return robotService.register(request);
    }

    /** 更新 AGV 档案（型号/载重/允许任务类型/归属桩） */
    @PutMapping("/{code}")
    public Robot update(@PathVariable String code, @Valid @RequestBody RobotUpsertRequest request) {
        return robotService.update(code, request);
    }

    /** 注销 AGV（仅离线、无在途任务） */
    @DeleteMapping("/{code}")
    public Map<String, Object> deregister(@PathVariable String code) {
        robotService.deregister(code);
        return Map.of("success", true, "robot", code);
    }

    /** 手动触发回充（空闲/执行完任务车辆立即生成充电任务） */
    @PostMapping("/{code}/charge")
    public Map<String, Object> charge(@PathVariable String code) {
        var task = engine.requestCharge(code);
        return Map.of("success", true, "robot", code, "taskId", task.getId());
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
