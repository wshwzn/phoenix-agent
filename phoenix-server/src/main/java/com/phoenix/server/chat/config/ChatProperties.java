package com.phoenix.server.chat.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 单次对话的工具轮数与调用数量预算，二者分别约束多轮和同轮多调用。
 */
@ConfigurationProperties(prefix = "phoenix.chat")
public record ChatProperties(int maxToolRounds, int maxToolCalls) {

    /**
     * 限定工具预算，避免配置错误导致无限循环。
     */
    public ChatProperties {
        if (maxToolRounds < 1 || maxToolRounds > 8 || maxToolCalls < 1 || maxToolCalls > 10) {
            throw new IllegalArgumentException("工具轮数须在 1 到 8 之间，调用次数须在 1 到 10 之间");
        }
    }
}
