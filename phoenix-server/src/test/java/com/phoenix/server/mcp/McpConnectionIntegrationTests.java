package com.phoenix.server.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 通过独立随机端口的官方 SDK 协议服务验证控制端的完整 HTTP 检查链路。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public class McpConnectionIntegrationTests {

    private static ConfigurableApplicationContext fixtureContext;

    @Autowired
    private TestRestTemplate restTemplate;

    /**
     * 启动独立协议测试服务，并把其随机地址交给生产客户端配置。
     */
    @DynamicPropertySource
    static void configureMcpEndpoint(DynamicPropertyRegistry registry) {
        fixtureContext = new SpringApplicationBuilder(FixtureConfiguration.class)
                .registerShutdownHook(false)
                .run("--server.port=0", "--server.address=127.0.0.1",
                        "--spring.application.name=mcp-protocol-fixture", "--spring.main.banner-mode=off");
        int fixturePort = ((ServletWebServerApplicationContext) fixtureContext).getWebServer().getPort();
        registry.add("phoenix.mcp.server-url", () -> "http://127.0.0.1:" + fixturePort + "/mcp");
        registry.add("phoenix.mcp.request-timeout", () -> "3s");
    }

    /**
     * 测试结束时释放协议服务与监听端口。
     */
    @AfterAll
    static void closeFixture() {
        if (fixtureContext != null) {
            fixtureContext.close();
        }
    }

    /**
     * HTTP 入口实际经过客户端握手、工具发现、调用和结果解析。
     */
    @Test
    void shouldCheckConnectionThroughBackendHttpEndpoint() {
        var response = restTemplate.getForEntity("/api/mcp/check", JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        var body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.path("transport").asText()).isEqualTo("STREAMABLE_HTTP");
        assertThat(body.path("tools").get(0).asText()).isEqualTo("ping");
        assertThat(body.path("ping").path("service").asText()).isEqualTo("phoenix-mcp-server");
        assertThat(body.path("ping").path("version").asText()).isEqualTo("0.0.1-SNAPSHOT");
        assertThat(body.path("ping").path("status").asText()).isEqualTo("PONG");
    }

    /**
     * 前一次关闭短会话后，下一次请求仍能重新建立连接。
     */
    @Test
    void shouldAllowRepeatedConnectionChecks() {
        for (int attempt = 0; attempt < 2; attempt++) {
            var response = restTemplate.getForEntity("/api/mcp/check", JsonNode.class);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().path("ping").path("status").asText()).isEqualTo("PONG");
        }
    }

    /**
     * 只用于协议集成测试的独立服务，不加载 Agent 业务 Bean。
     */
    @TestConfiguration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    public static class FixtureConfiguration {

        /**
         * 统计实际工具执行，供对话集成测试区分发现工具与调用工具。
         */
        @Bean
        AtomicInteger fixtureToolCalls() {
            return new AtomicInteger();
        }

        /**
         * 由对话测试切换模拟证据状态，不接触真实 Redis。
         */
        @Bean
        AtomicBoolean fixtureDependencyDown() {
            return new AtomicBoolean();
        }

        /**
         * 为协议测试服务配置 Jackson 2 序列化。
         */
        @Bean
        McpJsonMapper fixtureJsonMapper(ObjectMapper objectMapper) {
            return new JacksonMcpJsonMapper(objectMapper);
        }

        /**
         * 使用与生产相同的真实 Servlet 传输，而非模拟 HTTP 响应。
         */
        @Bean(destroyMethod = "")
        HttpServletStreamableServerTransportProvider fixtureTransport(McpJsonMapper mapper) {
            return HttpServletStreamableServerTransportProvider.builder()
                    .jsonMapper(mapper).mcpEndpoint("/mcp").keepAliveInterval(null).build();
        }

        /**
         * 将测试传输挂载到随机端口服务的 /mcp。
         */
        @Bean
        ServletRegistrationBean<HttpServletStreamableServerTransportProvider> fixtureServlet(
                HttpServletStreamableServerTransportProvider transport) {
            var registration = new ServletRegistrationBean<>(transport, "/mcp");
            registration.setAsyncSupported(true);
            return registration;
        }

        /**
     * 注册符合约定的协议替身工具，验证生产客户端而非替身客户端。
         */
        @Bean(destroyMethod = "closeGracefully")
        McpSyncServer fixtureServer(HttpServletStreamableServerTransportProvider transport, McpJsonMapper mapper,
                                   AtomicInteger fixtureToolCalls, AtomicBoolean fixtureDependencyDown) {
            var tool = McpSchema.Tool.builder().name("ping")
                    .inputSchema(new McpSchema.JsonSchema("object", Map.of(), List.of(), false, null, null))
                    .build();
            var builder = McpServer.sync(transport).jsonMapper(mapper)
                    .serverInfo("phoenix-mcp-server", "0.0.1-SNAPSHOT")
                    .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build())
                    .toolCall(tool, (exchange, request) -> {
                        fixtureToolCalls.incrementAndGet();
                        return McpSchema.CallToolResult.builder()
                                .isError(false)
                                .addTextContent("{\"service\":\"phoenix-mcp-server\",\"version\":\"0.0.1-SNAPSHOT\",\"status\":\"PONG\"}")
                                .build();
                    });
            for (String name : List.of("inspect_application", "inspect_redis", "read_recent_logs")) {
                var diagnosticTool = McpSchema.Tool.builder().name(name)
                        .inputSchema(new McpSchema.JsonSchema("object", Map.of("targetId", Map.of("type", "string")),
                                List.of("targetId"), false, null, null)).build();
                builder.toolCall(diagnosticTool, (exchange, request) -> {
                    fixtureToolCalls.incrementAndGet();
                    var result = diagnosticEvidence(name, fixtureDependencyDown.get());
                    try {
                        return McpSchema.CallToolResult.builder().isError(false)
                                .addTextContent(mapper.writeValueAsString(result)).build();
                    } catch (java.io.IOException exception) {
                        throw new IllegalStateException("测试证据序列化失败", exception);
                    }
                });
            }
            return builder.build();
        }

        /**
         * 构造显式标记为替身的确定性证据，不将其冒充真实采集。
         */
        private Map<String, Object> diagnosticEvidence(String tool, boolean down) {
            var result = new java.util.LinkedHashMap<String, Object>();
            result.put("targetId", "demo-local");
            result.put("observedAt", "2026-10-17T00:00:00Z");
            switch (tool) {
                case "inspect_application" -> {
                    result.put("kind", "application");
                    result.put("status", down ? "DEGRADED" : "UP");
                    result.put("probes", List.of(Map.of("name", "liveness", "status", "UP", "code", "OK", "httpStatus", 200),
                            Map.of("name", "health", "status", "UP", "code", "OK", "httpStatus", 200),
                            Map.of("name", "product", "status", down ? "DOWN" : "UP", "code", down ? "REDIS_UNAVAILABLE" : "OK",
                                    "httpStatus", down ? 503 : 200)));
                }
                case "inspect_redis" -> {
                    result.put("kind", "redis");
                    result.put("status", down ? "DOWN" : "UP");
                    result.put("code", down ? "CONNECTION_REFUSED" : "PONG");
                }
                case "read_recent_logs" -> {
                    result.put("kind", "logs");
                    result.put("status", "AVAILABLE");
                    result.put("lines", List.of(down ? "fixture: PRODUCT_QUERY_FAILED REDIS_UNAVAILABLE" : "fixture: PRODUCT_QUERY_OK"));
                }
                default -> throw new IllegalArgumentException("测试工具未登记");
            }
            return result;
        }
    }
}
