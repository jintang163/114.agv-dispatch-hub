package com.agv.domain.entity;

import com.agv.domain.enums.RobotStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

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
}
