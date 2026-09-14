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
    /** 低电量告警阈值（%）：空闲车低于该值自动回充 */
    private int lowBatteryThreshold = 25;
    /** 严重低电量阈值（%）：执行中车辆低于该值告警，完成当前任务后立即回充 */
    private int criticalBatteryThreshold = 15;
    /** 充满判定目标电量（%），达到后结束充电任务恢复空闲 */
    private int chargeTargetBattery = 95;
    /** 在桩充电停靠时长（秒），模拟器在该时长内涨电至目标值 */
    private int chargeDwellSeconds = 30;
    /** 电量巡检周期（毫秒） */
    private long batterySweepMs = 2000;
}
