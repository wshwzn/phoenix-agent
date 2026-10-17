package com.phoenix.server.mcp.dto;

/**
 * MCP ping 工具返回的服务名称、版本和状态。
 */
public record McpPingResult(String service, String version, String status) {
}
