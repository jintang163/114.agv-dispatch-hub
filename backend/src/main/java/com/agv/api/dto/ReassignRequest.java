package com.agv.api.dto;

/** 强制重分配请求，robotCode 为空则由调度器自动选择最近空闲机器人 */
public record ReassignRequest(String robotCode) {
}
