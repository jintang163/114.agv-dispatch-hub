package com.agv.domain.repository;

import com.agv.domain.entity.TaskEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TaskEventRepository extends JpaRepository<TaskEvent, Long> {
    List<TaskEvent> findByTaskIdOrderByOccurredAtAsc(Long taskId);
    List<TaskEvent> findTop100ByOrderByOccurredAtDesc();
}
