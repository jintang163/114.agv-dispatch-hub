package com.agv.scheduler;

import com.agv.scheduler.ReservationStore.Window;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 把"机器人位置 → 取货点 → 卸货点"完整路径展开为时空预约窗口。
 *
 * 合并后的节点序列：leg1 + leg2 去掉重复的连接点（leg2 首节点）。
 * 每条边通行 stepSeconds；普通节点占用一个步长；
 * pickupAtJoin=true 时连接点停靠 pickupDwellSeconds（取货/拣选）；
 * 终点始终停靠 dropDwellSeconds（卸货）。
 *
 * 起步建模：
 *  - waitSeconds：调度要求在起点原地等待的时长（时间窗冲突避让），起点节点窗口相应延长；
 *  - halfEdgeKey / halfEdgeSeconds：抢占时车辆尚在旧路径某条边的后半段，
 *    先走完剩余半边到接管节点，期间继续占用该边，之后才进入新路径序列。
 */
@Component
public class TimelineBuilder {

    private final TopologyCache topology;
    private final SchedulerProperties props;

    public TimelineBuilder(TopologyCache topology, SchedulerProperties props) {
        this.topology = topology;
        this.props = props;
    }

    public List<Window> build(List<String> leg1, List<String> leg2, long startEpoch) {
        return build(leg1, leg2, startEpoch, 0, null, 0, true, props.getDropDwellSeconds());
    }

    /**
     * @param waitSeconds      发车前在序列首节点原地等待秒数
     * @param halfEdgeKey      抢占剩余半边的规范化边 key（无则 null）
     * @param halfEdgeSeconds  走完剩余半边所需秒数
     * @param pickupAtJoin     连接点（leg1 末节点）是否为取货点；载货后重规划/充电任务传 false
     * @param endDwellSeconds  终点停靠秒数（卸货 dropDwell；充电任务传 chargeDwell）
     */
    public List<Window> build(List<String> leg1, List<String> leg2, long startEpoch,
                              long waitSeconds, String halfEdgeKey, long halfEdgeSeconds,
                              boolean pickupAtJoin, int endDwellSeconds) {
        if (leg1.isEmpty() || leg2.isEmpty()) {
            return List.of();
        }

        // 合并去重：leg2 的首节点即 leg1 的末节点（连接点）
        List<String> seq = new ArrayList<>(leg1);
        for (int i = 1; i < leg2.size(); i++) {
            seq.add(leg2.get(i));
        }
        int joinIdx = leg1.size() - 1;
        int endIdx = seq.size() - 1;

        int step = props.getStepSeconds();
        int pickupDwell = props.getPickupDwellSeconds();
        int dropDwell = endDwellSeconds;

        List<Window> windows = new ArrayList<>();
        long t = startEpoch;

        // 抢占：走完旧边剩余半边
        if (halfEdgeKey != null && halfEdgeSeconds > 0) {
            windows.add(Window.edge(halfEdgeKey, t, t + halfEdgeSeconds));
            t += halfEdgeSeconds;
        }

        // 起点节点占用（含起步等待）；
        // 单节点序列（如充电任务车已在桩）起点即终点，按终点停靠时长预约
        boolean singleNode = endIdx == 0;
        boolean firstIsPickup = pickupAtJoin && joinIdx == 0;
        long firstHold = (singleNode ? dropDwell
                : (firstIsPickup ? pickupDwell : step)) + waitSeconds;
        windows.add(Window.node(seq.get(0), t, t + firstHold));
        t += firstHold;

        for (int i = 0; i < endIdx; i++) {
            String a = seq.get(i);
            String b = seq.get(i + 1);
            windows.add(Window.edge(topology.edgeKey(a, b), t, t + step));
            t += step;

            long dwell;
            if (i + 1 == endIdx) {
                dwell = dropDwell;             // 终点卸货
            } else if (pickupAtJoin && i + 1 == joinIdx) {
                dwell = pickupDwell;           // 取货点停靠
            } else {
                dwell = step;
            }
            windows.add(Window.node(b, t, t + dwell));
            t += dwell;
        }
        return windows;
    }
}
