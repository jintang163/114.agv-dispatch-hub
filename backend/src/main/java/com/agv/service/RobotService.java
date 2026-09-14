package com.agv.service;

import com.agv.api.dto.RobotUpsertRequest;
import com.agv.common.ApiException;
import com.agv.domain.entity.Robot;
import com.agv.domain.enums.NodeType;
import com.agv.domain.enums.RobotStatus;
import com.agv.domain.enums.TaskType;
import com.agv.domain.repository.MapNodeRepository;
import com.agv.domain.repository.RobotRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** AGV 注册与档案维护：编号、型号、载重能力、允许任务类型、归属充电桩 */
@Service
public class RobotService {

    private final RobotRepository robotRepository;
    private final MapNodeRepository nodeRepository;

    public RobotService(RobotRepository robotRepository, MapNodeRepository nodeRepository) {
        this.robotRepository = robotRepository;
        this.nodeRepository = nodeRepository;
    }

    @Transactional
    public Robot register(RobotUpsertRequest req) {
        String code = req.code() == null ? null : req.code().trim();
        if (code == null || code.isBlank()) {
            throw new ApiException("车辆编号不能为空");
        }
        if (robotRepository.findByCode(code).isPresent()) {
            throw new ApiException("车辆编号已存在: " + code);
        }
        validateNode(req.currentNode(), "初始节点");
        validateCharger(req.homeCharger());
        validateAllowedTypes(req.allowedTaskTypes());

        Robot r = new Robot();
        r.setCode(code);
        r.setName(req.name());
        r.setModel(req.model());
        r.setPayloadCapacity(req.payloadCapacity() == null ? 500 : req.payloadCapacity());
        r.setAllowedTaskTypes(normalizeAllowed(req.allowedTaskTypes()));
        r.setHomeCharger(blankToNull(req.homeCharger()));
        r.setCurrentNode(blankToNull(req.currentNode()));
        r.setBattery(req.battery() == null ? 100 : req.battery());
        r.setStatus(RobotStatus.OFFLINE);
        r.setLastHeartbeat(Instant.EPOCH);
        return robotRepository.save(r);
    }

    @Transactional
    public Robot update(String code, RobotUpsertRequest req) {
        Robot r = robotRepository.findByCode(code)
                .orElseThrow(() -> new ApiException("机器人不存在: " + code));
        validateCharger(req.homeCharger());
        validateAllowedTypes(req.allowedTaskTypes());

        if (req.name() != null) {
            r.setName(req.name());
        }
        if (req.model() != null) {
            r.setModel(req.model());
        }
        if (req.payloadCapacity() != null) {
            r.setPayloadCapacity(req.payloadCapacity());
        }
        if (req.allowedTaskTypes() != null) {
            r.setAllowedTaskTypes(normalizeAllowed(req.allowedTaskTypes()));
        }
        r.setHomeCharger(blankToNull(req.homeCharger()));
        if (req.battery() != null) {
            r.setBattery(req.battery());
        }
        // 仅离线车允许改锚点，避免与实时位置冲突
        if (req.currentNode() != null && r.getStatus() == RobotStatus.OFFLINE) {
            validateNode(req.currentNode(), "当前节点");
            r.setCurrentNode(req.currentNode());
        }
        return robotRepository.save(r);
    }

    /** 注销：仅允许离线且无在途任务的车辆，避免删除运行中设备 */
    @Transactional
    public void deregister(String code) {
        Robot r = robotRepository.findByCode(code)
                .orElseThrow(() -> new ApiException("机器人不存在: " + code));
        if (r.getStatus() != RobotStatus.OFFLINE) {
            throw new ApiException("仅离线 AGV 可注销（当前状态 " + r.getStatus().getLabel() + "）");
        }
        if (r.getCurrentTaskId() != null) {
            throw new ApiException("该 AGV 仍有在途任务，无法注销");
        }
        robotRepository.delete(r);
    }

    private void validateNode(String code, String label) {
        String c = blankToNull(code);
        if (c != null && nodeRepository.findByCode(c).isEmpty()) {
            throw new ApiException(label + "不存在: " + c);
        }
    }

    private void validateCharger(String code) {
        String c = blankToNull(code);
        if (c == null) {
            return;
        }
        var node = nodeRepository.findByCode(c)
                .orElseThrow(() -> new ApiException("归属充电桩不存在: " + c));
        if (node.getType() != NodeType.CHARGER) {
            throw new ApiException("节点 " + c + " 不是充电桩");
        }
    }

    private void validateAllowedTypes(List<TaskType> types) {
        if (types == null) {
            return;
        }
        if (types.contains(TaskType.CHARGING)) {
            throw new ApiException("充电任务由系统自动管理，无需在允许类型中指定");
        }
    }

    private List<String> normalizeAllowed(List<TaskType> types) {
        if (types == null || types.isEmpty()) {
            return new ArrayList<>();
        }
        return types.stream().filter(t -> t != TaskType.CHARGING).map(Enum::name).distinct().toList();
    }

    private String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }
}
