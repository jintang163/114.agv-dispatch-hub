package com.agv.domain.repository;

import com.agv.domain.entity.Task;
import com.agv.domain.enums.TaskStatus;
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
}
