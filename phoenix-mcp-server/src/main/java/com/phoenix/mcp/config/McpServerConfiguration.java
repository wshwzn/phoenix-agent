package com.phoenix.mcp.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.phoenix.mcp.tool.PingTool;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * 通过官方 Java SDK 暴露 Streamable HTTP，不引入 Spring AI。
 */
@Configuration(proxyBeanMethods = false)
public class McpServerConfiguration {

    /**
     * 复用 Spring Boot 的 Jackson 2 配置处理 MCP JSON。
     */
    @Bean
    public McpJsonMapper mcpJsonMapper(ObjectMapper objectMapper) {
        return new JacksonMcpJsonMapper(objectMapper);
    }

    /**
     * 创建有请求体上限和闲置会话回收的 HTTP 传输层。
     */
    @Bean(destroyMethod = "")
    public HttpServletStreamableServerTransportProvider mcpTransport(McpJsonMapper jsonMapper) {
        return HttpServletStreamableServerTransportProvider.builder()
                .jsonMapper(jsonMapper)
                .mcpEndpoint("/mcp")
                .maxRequestSize(16 * 1024)
                .keepAliveInterval(null)
                .sessionSweepInterval(Duration.ofMinutes(5))
                .build();
    }

    /**
     * 仅把 /mcp 映射到协议 Servlet，保留原有健康检查接口。
     */
    @Bean
    public ServletRegistrationBean<HttpServletStreamableServerTransportProvider> mcpServlet(
            HttpServletStreamableServerTransportProvider transport) {
        var registration = new ServletRegistrationBean<>(transport, "/mcp");
        registration.setName("phoenixMcpServlet");
        registration.setAsyncSupported(true);
        registration.setLoadOnStartup(1);
        return registration;
    }

    /**
     * 注册唯一的测试工具，并在应用停止时关闭协议会话。
     */
    @Bean(destroyMethod = "closeGracefully")
    public McpSyncServer mcpServer(HttpServletStreamableServerTransportProvider transport,
                                   McpJsonMapper jsonMapper, PingTool pingTool) {
        return McpServer.sync(transport)
                .jsonMapper(jsonMapper)
                .serverInfo(PingTool.SERVICE_NAME, PingTool.SERVICE_VERSION)
                .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build())
                .tools(pingTool.specification(jsonMapper))
                .build();
    }
}
