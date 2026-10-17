package com.phoenix.server.chat.dto;

/**
 * 本次工具请求的关联标识、参数、实际结果和执行状态；当前只在响应中返回。
 */
public record ToolCallTrace(String callId, String toolName, String arguments, String result,
                            String status, long durationMs) {
}
