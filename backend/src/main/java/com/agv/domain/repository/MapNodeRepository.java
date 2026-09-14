package com.agv.domain.repository;

import com.agv.domain.entity.MapNode;
import com.agv.domain.enums.NodeType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface MapNodeRepository extends JpaRepository<MapNode, Long> {
    Optional<MapNode> findByCode(String code);
    List<MapNode> findByType(NodeType type);
    List<MapNode> findByTypeOrderByCodeAsc(NodeType type);
}
