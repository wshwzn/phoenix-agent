package com.phoenix.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.mcp.client.DefaultMcpClient;
import dev.langchain4j.mcp.client.transport.http.StreamableHttpMcpTransport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真实 MCP 服务执行真实采集代码，目标 HTTP/Redis 使用隔离的协议替身。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DiagnosticMcpIntegrationTests {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final AtomicBoolean DOWN = new AtomicBoolean();
    private static HttpServer app;
    private static ServerSocket redis;
    private static Path logDirectory;
    private static final CompletableFuture<Void> REDIS_DONE = new CompletableFuture<>();
    @LocalServerPort
    private int port;

    /**
     * 用随机端口与临时路径覆盖所有生产采集地址，禁止连接用户的演示环境。
     */
    @DynamicPropertySource
    static void configureTargets(DynamicPropertyRegistry registry) throws IOException {
        logDirectory = Files.createTempDirectory("phoenix-diagnostic-test-");
        app = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        app.createContext("/", exchange -> {
            try (exchange) {
                String path = exchange.getRequestURI().getPath();
                boolean failed = DOWN.get() && path.equals("/api/demo/products/1");
                String body = path.equals("/api/demo/ping") ? "{\"application\":\"phoenix-demo\"}"
                        : path.equals("/actuator/health") ? "{\"status\":\"UP\"}"
                        : failed ? "{\"code\":\"REDIS_UNAVAILABLE\"}" : "{\"id\":1,\"name\":\"fixture\"}";
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(failed ? 503 : 200, bytes.length);
                exchange.getResponseBody().write(bytes);
            }
        });
        app.start();
        redis = new ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"));
        int redisPort = redis.getLocalPort();
        Thread.ofVirtual().start(DiagnosticMcpIntegrationTests::respondToRedis);
        registry.add("phoenix.diagnostic.application-base-url", () -> "http://127.0.0.1:" + app.getAddress().getPort());
        registry.add("phoenix.diagnostic.redis-host", () -> "127.0.0.1");
        registry.add("phoenix.diagnostic.redis-port", () -> redisPort);
        registry.add("phoenix.diagnostic.log-file", () -> logDirectory.resolve("diagnostic.log").toString());
    }

    /**
     * 协议替身只接受固定的 PING，不模拟完整 Redis 服务。
     */
    private static void respondToRedis() {
        try {
            while (!redis.isClosed()) {
                try (var socket = redis.accept()) {
                    socket.setSoTimeout(1000);
                    byte[] command = socket.getInputStream().readNBytes(14);
                    if (new String(command, StandardCharsets.US_ASCII).equals("*1\r\n$4\r\nPING\r\n")) {
                        socket.getOutputStream().write("+PONG\r\n".getBytes(StandardCharsets.US_ASCII));
                        socket.getOutputStream().flush();
                    }
                }
            }
        } catch (IOException exception) {
            if (!redis.isClosed()) {
                REDIS_DONE.completeExceptionally(exception);
                return;
            }
        }
        REDIS_DONE.complete(null);
    }

    /**
     * 同一 MCP 会话中验证正常、依赖停止、日志缺失与真实近期事件读取。
     */
    @Test
    void shouldCollectHealthyAndFailedEvidenceThroughRealMcp() throws Exception {
        try (var client = DefaultMcpClient.builder().key("diagnostic-integration-test")
                .protocolVersion("2025-11-25").initializationTimeout(Duration.ofSeconds(3))
                .toolExecutionTimeout(Duration.ofSeconds(5)).autoHealthCheck(false)
                .transport(StreamableHttpMcpTransport.builder().url("http://127.0.0.1:" + port + "/mcp")
                        .timeout(Duration.ofSeconds(5)).subsidiaryChannel(false).build()).build()) {
            var appResult = client.executeTool(request("inspect_application"));
            assertThat(appResult.isError()).isFalse();
            assertThat(JSON.readTree(appResult.resultText()).path("status").asText()).isEqualTo("UP");
            var redisResult = client.executeTool(request("inspect_redis"));
            assertThat(JSON.readTree(redisResult.resultText()).path("code").asText()).isEqualTo("PONG");
            var missing = client.executeTool(request("read_recent_logs"));
            assertThat(missing.isError()).isFalse();
            assertThat(JSON.readTree(missing.resultText()).path("status").asText()).isEqualTo("MISSING");

            DOWN.set(true);
            redis.close();
            REDIS_DONE.get(3, TimeUnit.SECONDS);
            Files.writeString(logDirectory.resolve("diagnostic.log"),
                    "2026-10-17T10:00:00.000+08:00 WARN  event=PRODUCT_QUERY_FAILED application=phoenix-demo dependency=redis code=REDIS_UNAVAILABLE productId=1\n");
            var failedApp = client.executeTool(request("inspect_application"));
            assertThat(failedApp.isError()).isFalse();
            assertThat(JSON.readTree(failedApp.resultText()).path("status").asText()).isEqualTo("DEGRADED");
            assertThat(failedApp.resultText()).contains("REDIS_UNAVAILABLE");
            var failedRedis = client.executeTool(request("inspect_redis"));
            assertThat(failedRedis.isError()).isFalse();
            assertThat(JSON.readTree(failedRedis.resultText()).path("status").asText()).isEqualTo("DOWN");
            var logs = client.executeTool(request("read_recent_logs"));
            assertThat(logs.isError()).isFalse();
            assertThat(JSON.readTree(logs.resultText()).path("lines")).hasSize(1);
            assertThat(logs.resultText()).contains("PRODUCT_QUERY_FAILED").doesNotContain(logDirectory.toString());
        }
    }

    /**
     * 构造只包含已登记目标的工具请求。
     */
    private ToolExecutionRequest request(String tool) {
        return ToolExecutionRequest.builder().name(tool).arguments("{\"targetId\":\"demo-local\"}").build();
    }

    /**
     * 释放测试监听与专用临时文件，不遗留后台采集服务。
     */
    @AfterAll
    static void closeFixtures() throws Exception {
        if (app != null) {
            app.stop(0);
        }
        if (redis != null) {
            redis.close();
            REDIS_DONE.get(3, TimeUnit.SECONDS);
        }
        if (logDirectory != null) {
            Files.deleteIfExists(logDirectory.resolve("diagnostic.log"));
            Files.deleteIfExists(logDirectory);
        }
    }
}
