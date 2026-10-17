package com.phoenix.server.mcp.dto;

import java.util.List;

/**
 * 一次协议握手、工具发现与固定 ping 调用的检查结果。
 */
public record McpConnectionResult(String transport, List<String> tools, McpPingResult ping) {
}
