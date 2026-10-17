package com.phoenix.mcp.diagnostic.service;

import java.util.Map;

/**
 * 固定目标的只读采集能力，不提供通用网络、文件或命令接口。
 */
public interface DiagnosticService {

    /**
     * 检查应用探活、健康检查和商品接口。
     */
    Map<String, Object> inspectApplication(String targetId);

    /**
     * 对登记的 Redis 执行不带参数的 PING。
     */
    Map<String, Object> inspectRedis(String targetId);

    /**
     * 读取固定诊断文件的最近若干条允许事件。
     */
    Map<String, Object> readRecentLogs(String targetId, int lines);
}
