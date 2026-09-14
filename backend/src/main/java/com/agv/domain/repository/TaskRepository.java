package com.agv.domain.repository;

import com.agv.domain.entity.Task;
import com.agv.domain.enums.TaskStatus;
import com.agv.domain.enums.TaskType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TaskRepository extends JpaRepository<Task, Long> {
    List<Task> findByStatusOrderByCreatedAtAsc(TaskStatus status);
    List<Task> findByStatusIn(List<TaskStatus> statuses);
    List<Task> findByRobotIdAndStatusIn(Long robotId, List<TaskStatus> statuses);
    Page<Task> findByStatus(TaskStatus status, Pageable pageable);
    long countByStatus(TaskStatus status);

    /** 某机器人是否已有指定类型、处于指定状态集合的任务（充电任务去重） */
    boolean existsByTypeAndRobotIdAndStatusIn(TaskType type, Long robotId, List<TaskStatus> statuses);

    /** 目标节点（如充电桩）上是否存在活动任务，用于充电桩占用判定 */
    boolean existsByTypeAndToNodeAndStatusIn(TaskType type, String toNode, List<TaskStatus> statuses);
}
