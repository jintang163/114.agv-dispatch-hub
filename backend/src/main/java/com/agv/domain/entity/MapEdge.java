package com.agv.domain.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

/** 地图边（AGV 通道），bidirectional=true 时视为双向边 */
@Getter
@Setter
@Entity
@Table(name = "map_edge",
        uniqueConstraints = @UniqueConstraint(columnNames = {"from_node", "to_node"}))
public class MapEdge {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "from_node", nullable = false, length = 32)
    private String fromNode;

    @Column(name = "to_node", nullable = false, length = 32)
    private String toNode;

    /** 是否双向通行 */
    @Column(nullable = false)
    private Boolean bidirectional = true;

    /** 边权重（距离/代价，A* 使用） */
    @Column(nullable = false)
    private Integer weight = 1;

    /** 是否被物理阻塞（施工/货损），阻塞后边不参与寻路 */
    @Column(nullable = false)
    private Boolean blocked = false;
}
