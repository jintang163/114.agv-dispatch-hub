package com.agv.scheduler;

import com.agv.domain.entity.MapNode;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * A* 寻路：节点坐标做曼哈顿启发值（网格仓库地图），边权来自拓扑。
 * 返回包含起点、终点的有序节点列表；不可达返回空列表。
 */
@Component
public class AStarPlanner {

    private final TopologyCache topology;

    public AStarPlanner(TopologyCache topology) {
        this.topology = topology;
    }

    public List<String> plan(String start, String goal) {
        if (!topology.contains(start) || !topology.contains(goal)) {
            return List.of();
        }
        if (start.equals(goal)) {
            return List.of(start);
        }

        Map<String, Integer> gScore = new HashMap<>();
        Map<String, String> cameFrom = new HashMap<>();
        Set<String> closed = new HashSet<>();
        // 小顶堆：f 升序，seq 做稳定 tie-break
        PriorityQueue<HeapNode> heap = new PriorityQueue<>(
                Comparator.comparingInt((HeapNode n) -> n.f).thenComparingLong(n -> n.seq));
        Set<String> open = new HashSet<>();
        long[] seq = {0};

        gScore.put(start, 0);
        heap.add(new HeapNode(heuristic(start, goal), ++seq[0], start));
        open.add(start);

        while (!heap.isEmpty()) {
            HeapNode cur = heap.poll();
            String current = cur.code;
            if (!open.contains(current)) {
                continue; // 旧条目，已被更优条目替代
            }
            open.remove(current);
            if (current.equals(goal)) {
                return reconstruct(cameFrom, current);
            }
            closed.add(current);

            for (Map.Entry<String, Integer> e : topology.neighbors(current).entrySet()) {
                String next = e.getKey();
                if (closed.contains(next)) {
                    continue;
                }
                int tentative = gScore.get(current) + e.getValue();
                if (tentative < gScore.getOrDefault(next, Integer.MAX_VALUE)) {
                    cameFrom.put(next, current);
                    gScore.put(next, tentative);
                    heap.add(new HeapNode(tentative + heuristic(next, goal), ++seq[0], next));
                    open.add(next);
                }
            }
        }
        return List.of();
    }

    /** 最短路径总代价（用于挑选最近机器人），不可达为 MAX */
    public int distance(String start, String goal) {
        List<String> path = plan(start, goal);
        if (path.isEmpty()) {
            return Integer.MAX_VALUE;
        }
        int dist = 0;
        for (int i = 1; i < path.size(); i++) {
            dist += topology.edgeWeight(path.get(i - 1), path.get(i)).orElse(1);
        }
        return dist;
    }

    private int heuristic(String a, String b) {
        MapNode na = topology.node(a).orElseThrow();
        MapNode nb = topology.node(b).orElseThrow();
        return Math.abs(na.getX() - nb.getX()) + Math.abs(na.getY() - nb.getY());
    }

    private List<String> reconstruct(Map<String, String> cameFrom, String current) {
        LinkedList<String> path = new LinkedList<>();
        path.addFirst(current);
        String c = current;
        while (cameFrom.containsKey(c)) {
            c = cameFrom.get(c);
            path.addFirst(c);
        }
        return path;
    }

    private record HeapNode(int f, long seq, String code) {}
}
