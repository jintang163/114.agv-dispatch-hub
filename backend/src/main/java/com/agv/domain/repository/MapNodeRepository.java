package com.agv.domain.repository;

import com.agv.domain.entity.MapNode;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface MapNodeRepository extends JpaRepository<MapNode, Long> {
    Optional<MapNode> findByCode(String code);
}
