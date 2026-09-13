package com.agv.scheduler;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;

/**
 * 基于 Redis ZSET 的时空预约表（时间窗 reservation table）。
 *
 * 每个节点 / 边一个 ZSET：member = arrival|release|机器人|任务ID，score = release。
 * 区间 [arrival, release) 冲突判定：他人 release &gt; 自己 arrival 且 他人 arrival &lt; 自己 release。
 * 双向边规范化为 min&gt;max，对向行驶争抢同一把"边锁"，杜绝正面相撞。
 * 单条路径的全部窗口通过 Lua 原子 check-and-add，要么全部成功要么不写入。
 */
@Component
public class ReservationStore {

    private static final String NODE_PREFIX = "res:node:";
    private static final String EDGE_PREFIX = "res:edge:";
    private static final String TASK_PREFIX = "res:task:";

    private static final String RESERVE_LUA = """
            local taskIndexKey = ARGV[#ARGV-1]
            local count = tonumber(ARGV[#ARGV])
            for i = 1,count do
                local key = KEYS[i]
                local arrival = tonumber(ARGV[(i-1)*3+1])
                local release = tonumber(ARGV[(i-1)*3+2])
                local member = ARGV[(i-1)*3+3]
                -- score 即他人 release；只需检查 release > 自己 arrival 的候选
                local candidates = redis.call('ZRANGEBYSCORE', key, '('..arrival, '+inf')
                for _, c in ipairs(candidates) do
                    local otherArrival = tonumber(string.match(c, '^(-?%d+)'))
                    if otherArrival and otherArrival < release then
                        return 0
                    end
                end
            end
            for i = 1,count do
                redis.call('ZADD', KEYS[i], ARGV[(i-1)*3+2], ARGV[(i-1)*3+3])
                redis.call('SADD', taskIndexKey, KEYS[i] .. '|' .. ARGV[(i-1)*3+3])
            end
            return 1
            """;

    /**
     * KEYS: 窗口 zset
     * ARGV: 每组 arrival, release, 前缀(未用)；随后是忽略任务 id 列表，末尾为 id 数量
     * 成员以 "|任务id" 结尾；命中忽略列表的成员（自己/被抢占任务）不参与冲突判定
     */
    private static final String CHECK_LUA = """
            local nignore = tonumber(ARGV[#ARGV])
            local suffixes = {}
            for i = 0,nignore-1 do
                suffixes[i+1] = '|' .. ARGV[#ARGV-1-nignore+1+i]
            end
            local wcount = (#ARGV - 1 - nignore) / 3
            for i = 1,wcount do
                local key = KEYS[i]
                local arrival = tonumber(ARGV[(i-1)*3+1])
                local release = tonumber(ARGV[(i-1)*3+2])
                local candidates = redis.call('ZRANGEBYSCORE', key, '('..arrival, '+inf')
                for _, c in ipairs(candidates) do
                    local ignored = false
                    for _, suffix in ipairs(suffixes) do
                        if #suffix > 1 and string.sub(c, -#suffix) == suffix then
                            ignored = true
                            break
                        end
                    end
                    if not ignored then
                        local otherArrival = tonumber(string.match(c, '^(-?%d+)'))
                        if otherArrival and otherArrival < release then
                            return 0
                        end
                    end
                end
            end
            return 1
            """;

    private final StringRedisTemplate redis;
    private final DefaultRedisScript<Long> reserveScript;
    private final DefaultRedisScript<Long> checkScript;

    public ReservationStore(StringRedisTemplate redis) {
        this.redis = redis;
        this.reserveScript = new DefaultRedisScript<>(RESERVE_LUA, Long.class);
        this.checkScript = new DefaultRedisScript<>(CHECK_LUA, Long.class);
    }

    /**
     * 仅检查不写入；忽略 ignoreTaskIds 对应任务已有的窗口。
     * 用途：① 重规划时忽略自身旧窗口；② 抢占时忽略"自己 + 被抢占任务"的窗口。
     */
    public boolean freeWindowsIgnoringTasks(List<Long> ignoreTaskIds, List<Window> windows) {
        if (windows.isEmpty()) {
            return true;
        }
        List<Long> ignores = ignoreTaskIds == null ? List.of() : ignoreTaskIds;
        List<String> keys = new ArrayList<>(windows.size());
        List<String> args = new ArrayList<>(windows.size() * 3 + ignores.size() + 1);
        for (Window w : windows) {
            keys.add(w.key());
            args.add(String.valueOf(w.arrival()));
            args.add(String.valueOf(w.release()));
            args.add(w.arrival() + "|" + w.release() + "|");
        }
        for (Long id : ignores) {
            args.add(String.valueOf(id));
        }
        args.add(String.valueOf(ignores.size()));
        Long ok = redis.execute(checkScript, keys, args.toArray());
        return ok != null && ok == 1L;
    }

    /** 一个资源时间窗 */
    public record Window(String key, long arrival, long release) {
        public static Window node(String node, long arrival, long release) {
            return new Window(NODE_PREFIX + node, arrival, release);
        }

        public static Window edge(String edgeKey, long arrival, long release) {
            return new Window(EDGE_PREFIX + edgeKey, arrival, release);
        }
    }

    /** 原子预约一组窗口；失败不写入任何内容 */
    public boolean reserveWindows(String robotCode, long taskId, List<Window> windows) {
        if (windows.isEmpty()) {
            return true;
        }
        List<String> keys = new ArrayList<>(windows.size());
        List<String> args = new ArrayList<>(windows.size() * 3 + 2);
        for (Window w : windows) {
            keys.add(w.key());
            args.add(String.valueOf(w.arrival()));
            args.add(String.valueOf(w.release()));
            args.add(w.arrival() + "|" + w.release() + "|" + robotCode + "|" + taskId);
        }
        args.add(TASK_PREFIX + taskId);
        args.add(String.valueOf(windows.size()));
        Long ok = redis.execute(reserveScript, keys, args.toArray());
        return ok != null && ok == 1L;
    }

    /** 释放某任务持有的全部预约（完成 / 取消 / 抢占 / 故障） */
    public void releaseTask(long taskId) {
        String indexKey = TASK_PREFIX + taskId;
        Set<String> entries = redis.opsForSet().members(indexKey);
        if (entries != null) {
            for (String entry : entries) {
                int sep = entry.indexOf('|');
                if (sep > 0) {
                    redis.opsForZSet().remove(entry.substring(0, sep), entry.substring(sep + 1));
                }
            }
        }
        redis.delete(indexKey);
    }

    /** 清理已过期时间窗（release &lt;= now），调度 tick 周期执行 */
    public void pruneExpired() {
        long now = Instant.now().getEpochSecond();
        pruneByPattern(NODE_PREFIX + "*", now);
        pruneByPattern(EDGE_PREFIX + "*", now);
    }

    private void pruneByPattern(String pattern, long now) {
        try (var scan = redis.scan(org.springframework.data.redis.core.ScanOptions
                .scanOptions().match(pattern).count(200).build())) {
            while (scan.hasNext()) {
                redis.opsForZSet().removeRangeByScore(scan.next(),
                        Double.NEGATIVE_INFINITY, now);
            }
        } catch (Exception ignore) {
            // 下个 tick 重试
        }
    }

    /** 查询节点占用情况（诊断用） */
    public List<Map<String, Object>> nodeWindows(String node) {
        Set<org.springframework.data.redis.core.ZSetOperations.TypedTuple<String>> tuples =
                redis.opsForZSet().rangeWithScores(NODE_PREFIX + node, 0, -1);
        List<Map<String, Object>> result = new ArrayList<>();
        if (tuples == null) {
            return result;
        }
        for (var t : tuples) {
            String[] parts = String.valueOf(t.getValue()).split("\\|", 4);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("arrival", parts.length > 0 ? Long.parseLong(parts[0]) : null);
            m.put("release", t.getScore());
            m.put("robot", parts.length > 2 ? parts[2] : null);
            m.put("taskId", parts.length > 3 ? Long.parseLong(parts[3]) : null);
            result.add(m);
        }
        return result;
    }
}
