package com.phoenix.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.mcp.client.DefaultMcpClient;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.mcp.client.transport.http.StreamableHttpMcpTransport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 验证实际 MCP 服务的工具发现、调用与安全边界，无需 Docker。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PhoenixMcpServerApplicationTests {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @LocalServerPort
    private int port;

    /**
     * 检查工具服务的真实 HTTP 健康接口。
     */
    @Test
    void shouldExposeHealthWithoutDockerAccess() {
        var response = restTemplate.getForEntity("/actuator/health", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo("{\"status\":\"UP\"}");
    }

    /**
     * 通过真实 HTTP 发现四个受控工具，并调用无参数 ping。
     */
    @Test
    void shouldDiscoverAndInvokePingOverHttp() throws Exception {
        try (var client = createClient()) {
            var tools = client.listTools();

            assertThat(tools).extracting(tool -> tool.name())
                    .containsExactly("ping", "inspect_application", "inspect_redis", "read_recent_logs");
            assertThat(tools.getFirst().parameters().properties()).isEmpty();
            assertThat(tools.getFirst().parameters().additionalProperties()).isFalse();

            var result = client.executeTool(ToolExecutionRequest.builder()
                    .name("ping").arguments("{}").build());
            var json = objectMapper.readTree(result.resultText());

            assertThat(result.isError()).isFalse();
            assertThat(json.path("service").asText()).isEqualTo("phoenix-mcp-server");
            assertThat(json.path("version").asText()).isEqualTo("0.0.1-SNAPSHOT");
            assertThat(json.path("status").asText()).isEqualTo("PONG");
        }
    }

    /**
     * 拒绝未注册工具，防止把连通性测试变成通用操作接口。
     */
    @Test
    void shouldRejectUnknownTool() throws Exception {
        try (var client = createClient()) {
            assertThatThrownBy(() -> client.executeTool(ToolExecutionRequest.builder()
                    .name("restart_app").arguments("{}").build()))
                    .isInstanceOf(RuntimeException.class);
        }
    }

    /**
     * 即使直接发起调用，也不能给固定工具附加额外参数。
     */
    @Test
    void shouldRejectUnexpectedArguments() throws Exception {
        try (var client = createClient()) {
            assertThatThrownBy(() -> client.executeTool(ToolExecutionRequest.builder()
                    .name("ping").arguments("{\"command\":\"ignored\"}").build()))
                    .isInstanceOf(RuntimeException.class);
        }
    }

    /**
     * 直接调用 MCP 也不能传任意目标、地址、路径或异常日志条数。
     */
    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"targetId\":\"unknown\"}", "{\"targetId\":\"demo-local\",\"url\":\"http://localhost\"}",
            "{\"targetId\":\"demo-local\",\"path\":\"../secrets\"}", "{\"targetId\":\"demo-local\",\"lines\":0}",
            "{\"targetId\":\"demo-local\",\"lines\":81}", "{\"targetId\":\"demo-local\",\"lines\":1.5}",
            "{\"targetId\":\"demo-local\",\"lines\":\"40\"}", "{\"targetId\":\"demo-local\",\"lines\":null}"})
    void shouldRejectUnsafeDiagnosticArguments(String arguments) throws Exception {
        try (var client = createClient()) {
            assertThatThrownBy(() -> client.executeTool(ToolExecutionRequest.builder()
                    .name("read_recent_logs").arguments(arguments).build())).isInstanceOf(RuntimeException.class);
        }
    }

    /**
     * 浏览器来源请求不能直接访问本机 MCP 端点。
     */
    @Test
    void shouldRejectBrowserOrigin() {
        var headers = new HttpHeaders();
        headers.setOrigin("https://untrusted.example");
        headers.setContentType(MediaType.APPLICATION_JSON);

        var response = restTemplate.postForEntity("/mcp", new HttpEntity<>("{}", headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    /**
     * 传输层拒绝超过上限的请求体，不交给工具处理。
     */
    @Test
    void shouldRejectOversizedRequest() {
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(HttpHeaders.ACCEPT, "application/json, text/event-stream");

        var response = restTemplate.postForEntity("/mcp",
                new HttpEntity<>("x".repeat(17 * 1024), headers), JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
    }

    /**
     * 使用与控制端一致的协议版本连接测试服务器的随机端口。
     */
    private McpClient createClient() {
        return DefaultMcpClient.builder()
                .key("mcp-server-integration-test")
                .protocolVersion("2025-11-25")
                .initializationTimeout(Duration.ofSeconds(3))
                .toolExecutionTimeout(Duration.ofSeconds(3))
                .subscribeToToolListChanges(false)
                .subscribeToPromptListChanges(false)
                .subscribeToResourceListChanges(false)
                .transport(StreamableHttpMcpTransport.builder()
                        .url("http://127.0.0.1:" + port + "/mcp")
                        .timeout(Duration.ofSeconds(3))
                        .subsidiaryChannel(false)
                        .build())
                .build();
    }
}
