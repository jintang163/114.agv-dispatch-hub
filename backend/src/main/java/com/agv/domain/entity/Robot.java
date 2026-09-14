package com.agv.domain.entity;

import com.agv.domain.enums.RobotStatus;
import com.agv.domain.enums.TaskType;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** AGV 机器人 */
@Getter
@Setter
@Entity
@Table(name = "robot")
public class Robot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** MQTT clientId / 业务编号，如 AGV-01 */
    @Column(nullable = false, unique = true, length = 32)
    private String code;

    @Column(length = 64)
    private String name;

    /** 车辆型号，如 FL-500（500kg 潜伏顶升式） */
    @Column(length = 48)
    private String model;

    /** 额定载重能力（kg） */
    @Column(name = "payload_capacity")
    private Integer payloadCapacity = 500;

    /** 允许执行的任务类型（空/null 表示全部允许；CHARGING 始终由系统隐式允许） */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "allowed_task_types", columnDefinition = "jsonb")
    private List<String> allowedTaskTypes = new ArrayList<>();

    /** 归属/默认充电桩节点 code（手动回充的首选，缺省就近选桩） */
    @Column(name = "home_charger", length = 32)
    private String homeCharger;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private RobotStatus status = RobotStatus.OFFLINE;

    /** 当前所在节点 code（空闲锚点 / 路径段起点） */
    @Column(name = "current_node", length = 32)
    private String currentNode;

    /** 最近一次心跳/状态上报时间 */
    @Column(name = "last_heartbeat")
    private Instant lastHeartbeat;

    /** 当前执行任务 */
    @Column(name = "current_task_id")
    private Long currentTaskId;

    /** 电量百分比 0-100 */
    private Integer battery = 100;

    /** 是否允许该车型执行该任务类型（充电任务对所有车型开放） */
    public boolean supports(TaskType type) {
        if (type == TaskType.CHARGING) {
            return true;
        }
        return allowedTaskTypes == null || allowedTaskTypes.isEmpty()
                || allowedTaskTypes.contains(type.name());
    }
}
