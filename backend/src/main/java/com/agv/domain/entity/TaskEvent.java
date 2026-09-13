package com.agv.domain.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/** 任务事件流水（状态变更 / 抢占 / 重分配 / 异常），用于审计与前端时间线 */
@Getter
@Setter
@Entity
@Table(name = "task_event", indexes = @Index(name = "idx_event_task", columnList = "task_id"))
public class TaskEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "task_id", nullable = false)
    private Long taskId;

    /** 事件类型：CREATED/ASSIGNED/PREEMPTED/REASSIGNED/STARTED/PROGRESS/COMPLETED/CANCELLED/EXCEPTION */
    @Column(nullable = false, length = 32)
    private String event;

    @Column(name = "robot_code", length = 32)
    private String robotCode;

    @Column(length = 255)
    private String detail;

    @Column(nullable = false, updatable = false)
    private Instant occurredAt;

    @PrePersist
    void prePersist() {
        if (occurredAt == null) {
            occurredAt = Instant.now();
        }
    }
}
