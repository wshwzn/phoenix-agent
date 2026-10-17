package com.phoenix.server.chat.service;

import com.phoenix.server.chat.dto.ChatResponse;

/**
 * 提供没有历史记忆的最小模型与 MCP 工具对话。
 */
public interface ChatService {

    /**
     * 让模型决定直接回答，或在预算内请求已允许的 MCP 工具。
     */
    ChatResponse chat(String message);
}
