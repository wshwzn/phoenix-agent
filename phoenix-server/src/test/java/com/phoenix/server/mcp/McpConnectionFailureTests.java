package com.phoenix.server.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * 使用只监听、不返回 HTTP 响应的本机端口验证启动隔离和请求超时。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class McpConnectionFailureTests {

    private static ServerSocket silentServer;

    @Autowired
    private TestRestTemplate restTemplate;

    /**
     * 为失败用例分配独立端口，不依赖其他本地服务的运行状态。
     */
    @DynamicPropertySource
    static void configureUnresponsiveEndpoint(DynamicPropertyRegistry registry) throws IOException {
        silentServer = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
        registry.add("phoenix.mcp.server-url", () -> "http://127.0.0.1:" + silentServer.getLocalPort() + "/mcp");
        registry.add("phoenix.mcp.request-timeout", () -> "250ms");
    }

    /**
     * 释放失败用例使用的监听端口。
     */
    @AfterAll
    static void closeSilentServer() throws IOException {
        if (silentServer != null) {
            silentServer.close();
        }
    }

    /**
     * MCP 对端不响应也不会阻止 Agent 后端启动和健康检查。
     */
    @Test
    void shouldStartWithoutResponsiveMcpServer() {
        var response = restTemplate.getForEntity("/actuator/health", JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().path("status").asText()).isEqualTo("UP");
    }

    /**
     * 检查请求在有限时间内返回 502，且不泄露内部网络异常。
     */
    @Test
    void shouldReturnSanitizedBadGatewayWithinTimeout() {
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            var response = restTemplate.getForEntity("/api/mcp/check", JsonNode.class);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
            assertThat(response.getBody().path("detail").asText())
                    .isEqualTo("MCP 连通性检查失败，请检查工具服务是否启动及连接配置是否正确");
            assertThat(response.getBody().toString()).doesNotContain("127.0.0.1", "stackTrace", "TimeoutException");
        });
    }
}
