package com.phoenix.server.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.phoenix.server.mcp.McpConnectionIntegrationTests.FixtureConfiguration;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 使用本机模拟 OpenAI HTTP 服务与真实 SDK MCP 服务验证整条对话链路，不调用付费模型。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ChatIntegrationTests {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String TEST_KEY = "test-placeholder-not-a-secret";
    private static final List<JsonNode> MODEL_REQUESTS = new CopyOnWriteArrayList<>();
    private static final AtomicInteger CALL_SEQUENCE = new AtomicInteger();
    private static ConfigurableApplicationContext mcpContext;
    private static HttpServer modelServer;
    private static AtomicInteger toolCalls;
    private static AtomicBoolean dependencyDown;

    @Autowired
    private TestRestTemplate restTemplate;

    /**
     * 为真实生产客户端提供独立的本机模型与 MCP 地址，完全覆盖外部环境配置。
     */
    @DynamicPropertySource
    static void configureLocalServices(DynamicPropertyRegistry registry) throws IOException {
        mcpContext = new SpringApplicationBuilder(FixtureConfiguration.class)
                .registerShutdownHook(false)
                .run("--server.port=0", "--server.address=127.0.0.1", "--spring.application.name=chat-mcp-fixture",
                        "--spring.main.banner-mode=off", "--phoenix.model.enabled=false");
        int mcpPort = ((ServletWebServerApplicationContext) mcpContext).getWebServer().getPort();
        toolCalls = mcpContext.getBean("fixtureToolCalls", AtomicInteger.class);
        dependencyDown = mcpContext.getBean("fixtureDependencyDown", AtomicBoolean.class);
        modelServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        modelServer.createContext("/v1/chat/completions", ChatIntegrationTests::handleModelRequest);
        modelServer.start();
        registry.add("phoenix.mcp.server-url", () -> "http://127.0.0.1:" + mcpPort + "/mcp");
        registry.add("phoenix.mcp.request-timeout", () -> "3s");
        registry.add("phoenix.model.enabled", () -> true);
        registry.add("phoenix.model.base-url", () -> "http://127.0.0.1:" + modelServer.getAddress().getPort() + "/v1");
        registry.add("phoenix.model.model-name", () -> "local-test-model");
        registry.add("phoenix.model.api-key", () -> TEST_KEY);
        registry.add("phoenix.model.request-timeout", () -> "3s");
        registry.add("phoenix.chat.max-tool-rounds", () -> 3);
        registry.add("phoenix.chat.max-tool-calls", () -> 3);
    }

    /**
     * 每个用例单独统计模型请求与实际工具执行次数。
     */
    @BeforeEach
    void resetCounters() {
        MODEL_REQUESTS.clear();
        toolCalls.set(0);
        dependencyDown.set(false);
    }

    /**
     * 关闭所有测试服务，避免后台监听端口残留。
     */
    @AfterAll
    static void closeServices() {
        if (modelServer != null) {
            modelServer.stop(0);
        }
        if (mcpContext != null) {
            mcpContext.close();
        }
    }

    /**
     * 普通问候由模型直接回答，发现工具不等于执行工具。
     */
    @Test
    void shouldAnswerGreetingWithoutExecutingTools() {
        var response = restTemplate.postForEntity("/api/chat", Map.of("message", "你好"), JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().path("answer").asText()).contains("你好");
        assertThat(response.getBody().path("toolCalls").size()).isZero();
        assertThat(toolCalls.get()).isZero();
        assertThat(MODEL_REQUESTS).hasSize(1);
        assertThat(MODEL_REQUESTS.getFirst().path("model").asText()).isEqualTo("local-test-model");
        assertThat(MODEL_REQUESTS.getFirst().path("messages").toString()).doesNotContain(TEST_KEY);
        assertThat(MODEL_REQUESTS.getFirst().path("tools").get(0).path("function").path("name").asText()).isEqualTo("ping");
    }

    /**
     * 模型先请求工具，第二次模型请求实际包含对应 tool_call_id 和结果。
     */
    @Test
    void shouldLetModelChoosePingAndReceiveRealToolResult() {
        var response = restTemplate.postForEntity("/api/chat", Map.of("message", "请检查 MCP 版本"), JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().path("answer").asText()).contains("PONG", "0.0.1-SNAPSHOT");
        var trace = response.getBody().path("toolCalls").get(0);
        assertThat(trace.path("toolName").asText()).isEqualTo("ping");
        assertThat(trace.path("status").asText()).isEqualTo("SUCCESS");
        assertThat(trace.path("result").asText()).contains("PONG");
        assertThat(toolCalls.get()).isEqualTo(1);
        assertThat(MODEL_REQUESTS).hasSize(2);
        var toolMessage = MODEL_REQUESTS.getLast().path("messages").get(3);
        assertThat(toolMessage.path("role").asText()).isEqualTo("tool");
        assertThat(toolMessage.path("tool_call_id").asText()).isEqualTo(trace.path("callId").asText());
        assertThat(toolMessage.path("content").asText()).contains("phoenix-mcp-server", "PONG");
    }

    /**
     * 同一响应中的多个工具请求各自执行，并完整回传给下一轮模型。
     */
    @Test
    void shouldPreserveMultipleToolCallsInOneRound() {
        var response = restTemplate.postForEntity("/api/chat", Map.of("message", "同轮两次检查"), JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().path("toolCalls").size()).isEqualTo(2);
        assertThat(toolCalls.get()).isEqualTo(2);
        assertThat(MODEL_REQUESTS.getLast().path("messages").size()).isEqualTo(5);
    }

    /**
     * 多轮工具结果不会只保留最后一轮，最终请求中仍有前轮工具消息。
     */
    @Test
    void shouldPreserveAllToolRounds() {
        var response = restTemplate.postForEntity("/api/chat", Map.of("message", "多轮两次检查"), JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().path("toolCalls").size()).isEqualTo(2);
        assertThat(toolCalls.get()).isEqualTo(2);
        assertThat(MODEL_REQUESTS).hasSize(3);
        assertThat(MODEL_REQUESTS.getLast().path("messages").size()).isEqualTo(6);
        assertThat(MODEL_REQUESTS.getLast().path("messages").get(3).path("role").asText()).isEqualTo("tool");
        assertThat(MODEL_REQUESTS.getLast().path("messages").get(5).path("role").asText()).isEqualTo("tool");
    }

    /**
     * 模型编造的修复工具被本地策略阻止，不会发给 MCP 服务。
     */
    @Test
    void shouldBlockUnknownTool() {
        var response = restTemplate.postForEntity("/api/chat", Map.of("message", "模拟未知工具"), JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(response.getBody().path("code").asText()).isEqualTo("INVALID_TOOL_REQUEST");
        assertThat(response.getBody().path("toolCalls").get(0).path("status").asText()).isEqualTo("BLOCKED");
        assertThat(toolCalls.get()).isZero();
    }

    /**
     * 模型为 ping 添加额外参数时在控制端阻止，不依赖模型自觉遵守说明。
     */
    @Test
    void shouldBlockUnexpectedArguments() {
        var response = restTemplate.postForEntity("/api/chat", Map.of("message", "模拟额外参数"), JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(response.getBody().path("code").asText()).isEqualTo("INVALID_TOOL_REQUEST");
        assertThat(toolCalls.get()).isZero();
    }

    /**
     * 模型持续索要工具时最多执行预算内三次，并保留已经执行的结果。
     */
    @Test
    void shouldStopRepeatedToolRequestsAtBudget() {
        var response = restTemplate.postForEntity("/api/chat", Map.of("message", "模拟无限调用"), JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody().path("code").asText()).isEqualTo("TOOL_BUDGET_EXCEEDED");
        assertThat(response.getBody().path("toolCalls").size()).isEqualTo(3);
        assertThat(toolCalls.get()).isEqualTo(3);
        assertThat(MODEL_REQUESTS).hasSize(4);
    }

    /**
     * 一批请求整体超过预算时不执行其中任何工具。
     */
    @Test
    void shouldRejectOversizedBatchBeforeExecutingAnyTool() {
        var response = restTemplate.postForEntity("/api/chat", Map.of("message", "模拟同轮四次"), JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(toolCalls.get()).isZero();
    }

    /**
     * 供应商错误不会把密钥或原始错误正文回传，也不自动重试付费请求。
     */
    @Test
    void shouldSanitizeModelErrorWithoutRetry() {
        var response = restTemplate.postForEntity("/api/chat", Map.of("message", "模拟模型错误"), JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(response.getBody().path("code").asText()).isEqualTo("MODEL_CALL_FAILED");
        assertThat(response.getBody().toString()).doesNotContain(TEST_KEY, "provider-internal-detail");
        assertThat(MODEL_REQUESTS).hasSize(1);
    }

    /**
     * 工具执行后模型失败时，错误响应仍保留实际发生的工具结果。
     */
    @Test
    void shouldKeepToolTraceWhenFinalModelCallFails() {
        var response = restTemplate.postForEntity("/api/chat", Map.of("message", "模拟工具后模型错误"), JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(response.getBody().path("toolCalls").size()).isEqualTo(1);
        assertThat(response.getBody().path("toolCalls").get(0).path("result").asText()).contains("PONG");
        assertThat(toolCalls.get()).isEqualTo(1);
    }

    /**
     * 不同请求不会隐式共享聊天历史。
     */
    @Test
    void shouldKeepRequestsStateless() {
        restTemplate.postForEntity("/api/chat", Map.of("message", "记住第一条消息"), JsonNode.class);
        var response = restTemplate.postForEntity("/api/chat", Map.of("message", "你好"), JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(MODEL_REQUESTS).hasSize(2);
        assertThat(MODEL_REQUESTS.getLast().path("messages").size()).isEqualTo(2);
        assertThat(MODEL_REQUESTS.getLast().path("messages").toString()).doesNotContain("记住第一条消息");
    }

    /**
     * 三轮只读检查全部反馈给模型，依赖故障证据不会被误当成工具执行错误。
     */
    @Test
    void shouldFeedAllDiagnosticEvidenceBackToModel() {
        dependencyDown.set(true);
        var response = restTemplate.postForEntity("/api/chat", Map.of("message", "检查 demo-local 商品查询故障"), JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().path("answer").asText()).contains("Redis", "连接失败");
        assertThat(response.getBody().path("toolCalls")).hasSize(3);
        for (var trace : response.getBody().path("toolCalls")) {
            assertThat(trace.path("status").asText()).isEqualTo("SUCCESS");
            assertThat(trace.path("arguments").asText()).contains("demo-local");
        }
        assertThat(toolCalls.get()).isEqualTo(3);
        assertThat(MODEL_REQUESTS).hasSize(4);
        assertThat(MODEL_REQUESTS.getLast().path("messages").toString())
                .contains("REDIS_UNAVAILABLE", "CONNECTION_REFUSED", "PRODUCT_QUERY_FAILED");
        assertThat(MODEL_REQUESTS.getFirst().path("tools")).hasSize(4);
    }

    /**
     * 正常证据同样经过三轮工具，脚本模型根据收到的结果返回正常结论。
     */
    @Test
    void shouldCompleteHealthyDiagnosticConversation() {
        var response = restTemplate.postForEntity("/api/chat", Map.of("message", "检查 demo-local 当前状态"), JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().path("answer").asText()).contains("检查正常");
        assertThat(toolCalls.get()).isEqualTo(3);
        assertThat(MODEL_REQUESTS.getLast().path("messages").toString()).contains("PRODUCT_QUERY_OK", "PONG");
    }

    /**
     * 根据测试场景生成标准兼容协议响应，模拟模型决定而非由业务关键词路由工具。
     */
    private static void handleModelRequest(HttpExchange exchange) throws IOException {
        try (exchange) {
            if (!"POST".equals(exchange.getRequestMethod())
                    || !("Bearer " + TEST_KEY).equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
                sendJson(exchange, 401, Map.of("error", Map.of("message", "test authorization missing")));
                return;
            }
            var request = JSON.readTree(exchange.getRequestBody());
            MODEL_REQUESTS.add(request.deepCopy());
            String userMessage = request.path("messages").get(1).path("content").asText();
            int completedTools = 0;
            for (var message : request.path("messages")) {
                if ("tool".equals(message.path("role").asText())) {
                    completedTools++;
                }
            }
            if (userMessage.equals("模拟模型错误") || (userMessage.equals("模拟工具后模型错误") && completedTools > 0)) {
                sendJson(exchange, 401, Map.of("error", Map.of("message", "provider-internal-detail " + TEST_KEY,
                        "type", "authentication_error")));
                return;
            }
            boolean needsTool = userMessage.contains("检查") || userMessage.startsWith("模拟");
            boolean diagnosis = userMessage.contains("demo-local");
            boolean callAgain = completedTools == 0 || userMessage.equals("模拟无限调用")
                    || (userMessage.equals("多轮两次检查") && completedTools < 2) || (diagnosis && completedTools < 3);
            Object message;
            String finishReason;
            if (needsTool && callAgain) {
                int count = userMessage.equals("同轮两次检查") ? 2 : userMessage.equals("模拟同轮四次") ? 4 : 1;
                var calls = new java.util.ArrayList<Map<String, Object>>();
                for (int index = 0; index < count; index++) {
                    String toolName = userMessage.equals("模拟未知工具") ? "restart_app" : "ping";
                    String arguments = userMessage.equals("模拟额外参数") ? "{\"extra\":true}" : "{}";
                    if (diagnosis) {
                        toolName = List.of("inspect_application", "inspect_redis", "read_recent_logs").get(completedTools);
                        arguments = "{\"targetId\":\"demo-local\"}";
                    }
                    calls.add(Map.of("id", "call-" + CALL_SEQUENCE.incrementAndGet(), "type", "function",
                            "function", Map.of("name", toolName, "arguments", arguments)));
                }
                message = Map.of("role", "assistant", "tool_calls", calls);
                finishReason = "tool_calls";
            } else {
                String answer = diagnosis ? request.path("messages").toString().contains("REDIS_UNAVAILABLE")
                        ? "脚本结论：商品查询失败，Redis 连接失败，需要进一步确认网络或服务状态。" : "脚本结论：当前检查正常。"
                        : completedTools == 0 ? "你好，我是 Phoenix。" : "MCP 服务返回 PONG，版本为 0.0.1-SNAPSHOT；这不代表目标应用健康。";
                message = Map.of("role", "assistant", "content", answer);
                finishReason = "stop";
            }
            sendJson(exchange, 200, Map.of("id", "local-completion", "object", "chat.completion",
                    "created", 1, "model", "local-test-model",
                    "choices", List.of(Map.of("index", 0, "message", message, "finish_reason", finishReason)),
                    "usage", Map.of("prompt_tokens", 10, "completion_tokens", 5, "total_tokens", 15)));
        }
    }

    /**
     * 向生产 OpenAI 客户端返回 UTF-8 JSON，明确验证实际 HTTP 协议。
     */
    private static void sendJson(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] bytes = JSON.writeValueAsString(body).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }
}
