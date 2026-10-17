package com.phoenix.server.mcp.config;

import dev.langchain4j.mcp.client.DefaultMcpClient;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.mcp.client.transport.http.StreamableHttpMcpTransport;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 按检查请求创建短会话，不在应用启动时连接外部 MCP 服务。
 */
@Component
@EnableConfigurationProperties(McpClientProperties.class)
public class McpClientFactory {

    private final McpClientProperties properties;

    /**
     * 注入固定的 MCP 连接配置。
     */
    public McpClientFactory(McpClientProperties properties) {
        this.properties = properties;
    }

    /**
     * 创建有超时且不跟随重定向的客户端，调用方负责关闭会话。
     */
    public McpClient createClient() {
        var transport = StreamableHttpMcpTransport.builder()
                .url(properties.serverUrl().toString())
                .timeout(properties.requestTimeout())
                .followRedirects(false)
                .subsidiaryChannel(false)
                .build();
        try {
            return DefaultMcpClient.builder()
                    .key("phoenix-target")
                    .clientName("phoenix-server")
                    .clientVersion("0.0.1-SNAPSHOT")
                    .protocolVersion("2025-11-25")
                    .initializationTimeout(properties.requestTimeout())
                    .toolExecutionTimeout(properties.requestTimeout())
                    .pingTimeout(properties.requestTimeout())
                    .autoHealthCheck(false)
                    .subscribeToToolListChanges(false)
                    .subscribeToPromptListChanges(false)
                    .subscribeToResourceListChanges(false)
                    .transport(transport)
                    .build();
        } catch (RuntimeException exception) {
            try {
                transport.close();
            } catch (IOException closeException) {
                exception.addSuppressed(closeException);
            }
            throw exception;
        }
    }
}
