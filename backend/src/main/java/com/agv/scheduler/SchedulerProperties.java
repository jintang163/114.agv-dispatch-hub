package com.agv.scheduler;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "agv")
public class SchedulerProperties {
    /** 调度 tick（毫秒） */
    private long dispatchTickMs = 1000;
    /** AGV 心跳超时（毫秒） */
    private long heartbeatTimeoutMs = 8000;
    /** 每个时间窗步长（秒），即 AGV 通过一条边的耗时 */
    private int stepSeconds = 2;
    /** 取货/拣选停靠时长（秒） */
    private int pickupDwellSeconds = 4;
    /** 卸货停靠时长（秒） */
    private int dropDwellSeconds = 4;
}
