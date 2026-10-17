package com.phoenix.server.mcp.service;

import com.phoenix.server.mcp.dto.McpConnectionResult;

/**
 * 提供不依赖模型的 MCP 连通性检查。
 */
public interface McpConnectionService {

    /**
     * 发现远程工具并调用固定的 ping 工具。
     */
    McpConnectionResult checkConnection();
}
