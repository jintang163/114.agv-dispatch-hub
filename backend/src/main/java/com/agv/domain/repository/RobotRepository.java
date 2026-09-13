package com.agv.domain.repository;

import com.agv.domain.entity.Robot;
import com.agv.domain.enums.RobotStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RobotRepository extends JpaRepository<Robot, Long> {
    Optional<Robot> findByCode(String code);
    List<Robot> findAllByOrderByCodeAsc();
    List<Robot> findByStatus(RobotStatus status);
}
