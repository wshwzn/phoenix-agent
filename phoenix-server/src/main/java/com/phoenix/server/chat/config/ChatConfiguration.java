package com.phoenix.server.chat.config;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 按配置启用模型，构建客户端本身不发送模型请求。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({ModelProperties.class, ChatProperties.class})
public class ChatConfiguration {

    /**
     * 创建不记录原始请求、不自动重试的兼容协议客户端。
     */
    @Bean
    @ConditionalOnProperty(prefix = "phoenix.model", name = "enabled", havingValue = "true")
    public ChatModel chatModel(ModelProperties properties) {
        return OpenAiChatModel.builder()
                .baseUrl(properties.baseUrl())
                .modelName(properties.modelName())
                .apiKey(properties.apiKey())
                .timeout(properties.requestTimeout())
                .maxTokens(properties.maxOutputTokens())
                .maxRetries(0)
                .logRequests(false)
                .logResponses(false)
                .build();
    }
}
