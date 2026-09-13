package com.agv.domain.entity;

import com.agv.domain.enums.NodeType;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

/** 地图拓扑节点 */
@Getter
@Setter
@Entity
@Table(name = "map_node")
public class MapNode {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 节点编码，如 P-01 / J-03 / D-01 */
    @Column(nullable = false, unique = true, length = 32)
    private String code;

    @Column(nullable = false)
    private Integer x;

    @Column(nullable = false)
    private Integer y;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private NodeType type = NodeType.JUNCTION;

    private String name;
}
