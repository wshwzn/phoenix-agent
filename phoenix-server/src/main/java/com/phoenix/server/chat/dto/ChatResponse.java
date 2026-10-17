package com.phoenix.server.chat.dto;

import java.util.List;

/**
 * 返回最终回答和本次请求内各轮工具调用明细，不表示已经持久化。
 */
public record ChatResponse(String requestId, String answer, List<ToolCallTrace> toolCalls) {
}
