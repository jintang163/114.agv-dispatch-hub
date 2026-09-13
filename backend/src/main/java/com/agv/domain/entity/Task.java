package com.agv.domain.entity;

import com.agv.domain.enums.TaskPhase;
import com.agv.domain.enums.TaskPriority;
import com.agv.domain.enums.TaskStatus;
import com.agv.domain.enums.TaskType;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** 搬运/拣选任务 */
@Getter
@Setter
@Entity
@Table(name = "task", indexes = {
        @Index(name = "idx_task_status", columnList = "status"),
        @Index(name = "idx_task_robot", columnList = "robot_id")
})
public class Task {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** WMS 外部任务号（可选，幂等用） */
    @Column(name = "external_no", unique = true, length = 64)
    private String externalNo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private TaskType type = TaskType.TRANSPORT;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private TaskPriority priority = TaskPriority.MEDIUM;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private TaskStatus status = TaskStatus.PENDING;

    @Enumerated(EnumType.STRING)
    @Column(length = 24)
    private TaskPhase phase;

    @Column(name = "from_node", nullable = false, length = 32)
    private String fromNode;

    @Column(name = "to_node", nullable = false, length = 32)
    private String toNode;

    /** WMS 期望完成时间（截止时间），越临近越紧急 */
    @Column(name = "deadline")
    private Instant deadline;

    /** 分配的机器人 */
    @Column(name = "robot_id")
    private Long robotId;

    /** 规划路径（有序节点 code 列表），供地图展示与执行 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "planned_path", columnDefinition = "jsonb")
    private List<String> plannedPath = new ArrayList<>();

    /** 实际已走的节点（用于展示） */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "actual_path", columnDefinition = "jsonb")
    private List<String> actualPath = new ArrayList<>();

    @Column(length = 255)
    private String remark;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "assigned_at")
    private Instant assignedAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
