package com.phoenix.server.chat.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 单轮独立对话输入，不接收系统提示词、模型地址、密钥或历史消息。
 */
public record ChatRequest(@NotBlank @Size(max = 4000) String message) {
}
