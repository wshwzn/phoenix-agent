package com.phoenix.mcp.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.phoenix.mcp.tool.PingTool;
import com.phoenix.mcp.tool.DiagnosticTools;
import com.phoenix.mcp.diagnostic.config.DiagnosticProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
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
@EnableConfigurationProperties(DiagnosticProperties.class)
public class McpServerConfiguration {

    /**
     * 复制 Spring Boot 的 Jackson 2 配置，处理 MCP 时额外拒绝重复字段。
     */
    @Bean
    public McpJsonMapper mcpJsonMapper(ObjectMapper objectMapper) {
        return new JacksonMcpJsonMapper(objectMapper.copy()
                .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION));
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
     * 注册连通性与固定目标只读工具，并在停止时关闭协议会话。
     */
    @Bean(destroyMethod = "closeGracefully")
    public McpSyncServer mcpServer(HttpServletStreamableServerTransportProvider transport,
                                   McpJsonMapper jsonMapper, PingTool pingTool, DiagnosticTools diagnosticTools) {
        var tools = new java.util.ArrayList<>(diagnosticTools.specifications(jsonMapper));
        tools.addFirst(pingTool.specification(jsonMapper));
        return McpServer.sync(transport)
                .jsonMapper(jsonMapper)
                .serverInfo(PingTool.SERVICE_NAME, PingTool.SERVICE_VERSION)
                .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build())
                .tools(tools)
                .build();
    }
}
