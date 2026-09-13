package com.agv.api;

import com.agv.common.ApiException;
import com.agv.domain.entity.MapEdge;
import com.agv.domain.entity.MapNode;
import com.agv.domain.repository.MapEdgeRepository;
import com.agv.domain.repository.MapNodeRepository;
import com.agv.scheduler.DispatchEngine;
import com.agv.scheduler.TopologyCache;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 地图拓扑查询与通道阻塞管控 */
@RestController
@RequestMapping("/api/map")
public class MapController {

    private final MapNodeRepository nodeRepository;
    private final MapEdgeRepository edgeRepository;
    private final TopologyCache topology;
    private final DispatchEngine engine;

    public MapController(MapNodeRepository nodeRepository,
                         MapEdgeRepository edgeRepository,
                         TopologyCache topology,
                         DispatchEngine engine) {
        this.nodeRepository = nodeRepository;
        this.edgeRepository = edgeRepository;
        this.topology = topology;
        this.engine = engine;
    }

    @GetMapping
    public Map<String, Object> map() {
        return Map.of("nodes", nodeRepository.findAll(), "edges", edgeRepository.findAll());
    }

    @GetMapping("/nodes")
    public List<MapNode> nodes() {
        return nodeRepository.findAll();
    }

    /** 阻塞/恢复一条通道，阻塞后受影响在途任务自动重新规划 */
    @PutMapping("/edges/block")
    @Transactional
    public Map<String, Object> blockEdge(@RequestBody Map<String, String> body) {
        String from = body.get("from");
        String to = body.get("to");
        boolean blocked = Boolean.parseBoolean(body.getOrDefault("blocked", "true"));
        if (!topology.contains(from) || !topology.contains(to)) {
            throw new ApiException("节点不存在");
        }

        List<MapEdge> updated = new ArrayList<>();
        for (MapEdge edge : edgeRepository.findAll()) {
            boolean dir = edge.getFromNode().equals(from) && edge.getToNode().equals(to);
            boolean rev = edge.getFromNode().equals(to) && edge.getToNode().equals(from);
            if (dir || (rev && Boolean.TRUE.equals(edge.getBidirectional()))) {
                edge.setBlocked(blocked);
                updated.add(edgeRepository.save(edge));
            }
        }
        if (updated.isEmpty()) {
            throw new ApiException("该方向不存在通道: " + from + " -> " + to);
        }
        topology.refresh();
        if (blocked) {
            engine.replanTasksUsingEdge(from, to);
        }
        return Map.of("success", true, "updated", updated.size(), "blocked", blocked);
    }
}
