package com.agv.scheduler;

import com.agv.domain.entity.MapEdge;
import com.agv.domain.entity.MapNode;
import com.agv.domain.repository.MapEdgeRepository;
import com.agv.domain.repository.MapNodeRepository;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 地图拓扑缓存：邻接表。
 * 边 A->B 且 bidirectional=true 时同时建立 B->A；blocked=true 的边不参与寻路。
 */
@Component
public class TopologyCache {

    private final MapNodeRepository nodeRepository;
    private final MapEdgeRepository edgeRepository;

    @Getter
    private volatile Map<String, MapNode> nodes = Map.of();

    /** code -> (neighbor -> weight) */
    private volatile Map<String, Map<String, Integer>> adjacency = Map.of();

    public TopologyCache(MapNodeRepository nodeRepository, MapEdgeRepository edgeRepository) {
        this.nodeRepository = nodeRepository;
        this.edgeRepository = edgeRepository;
    }

    @PostConstruct
    public synchronized void refresh() {
        Map<String, MapNode> n = new HashMap<>();
        for (MapNode node : nodeRepository.findAll()) {
            n.put(node.getCode(), node);
        }
        Map<String, Map<String, Integer>> adj = new HashMap<>();
        for (String node : n.keySet()) {
            adj.put(node, new HashMap<>());
        }
        for (MapEdge edge : edgeRepository.findAll()) {
            if (Boolean.TRUE.equals(edge.getBlocked())) {
                continue;
            }
            addEdge(adj, edge.getFromNode(), edge.getToNode(), edge.getWeight());
            if (Boolean.TRUE.equals(edge.getBidirectional())) {
                addEdge(adj, edge.getToNode(), edge.getFromNode(), edge.getWeight());
            }
        }
        this.nodes = Collections.unmodifiableMap(n);
        this.adjacency = Collections.unmodifiableMap(adj);
    }

    private void addEdge(Map<String, Map<String, Integer>> adj, String from, String to, Integer weight) {
        adj.computeIfAbsent(from, k -> new HashMap<>())
                .put(to, weight == null ? 1 : weight);
    }

    public boolean contains(String code) {
        return nodes.containsKey(code);
    }

    public Map<String, Integer> neighbors(String code) {
        return adjacency.getOrDefault(code, Map.of());
    }

    public Optional<MapNode> node(String code) {
        return Optional.ofNullable(nodes.get(code));
    }

    /** 两节点间的边权重（无直连返回空） */
    public OptionalInt edgeWeight(String from, String to) {
        Integer w = neighbors(from).get(to);
        return w == null ? OptionalInt.empty() : OptionalInt.of(w);
    }

    /** 边预约 key：规范化方向，对向行驶争抢同一把锁（防正面相撞） */
    public String edgeKey(String a, String b) {
        return a.compareTo(b) <= 0 ? a + ">" + b : b + ">" + a;
    }
}
