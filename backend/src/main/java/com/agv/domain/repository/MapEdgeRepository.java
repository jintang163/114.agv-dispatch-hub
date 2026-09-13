package com.agv.domain.repository;

import com.agv.domain.entity.MapEdge;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MapEdgeRepository extends JpaRepository<MapEdge, Long> {
    boolean existsByFromNodeAndToNode(String fromNode, String toNode);
}
