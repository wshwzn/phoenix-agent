package com.phoenix.server.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.phoenix.server.chat.config.ChatProperties;
import com.phoenix.server.chat.exception.ChatExecutionException;
import com.phoenix.server.chat.service.impl.ChatServiceImpl;
import com.phoenix.server.mcp.config.McpClientFactory;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.service.tool.ToolExecutionResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 独立验证工具授权、结果有效性、预算与资源释放。
 */
class ChatServiceTests {

    private ObjectProvider<ChatModel> provider;
    private ChatModel model;
    private McpClient client;
    private McpClientFactory factory;

    /**
     * 准备不访问网络的模型和工具客户端替身。
     */
    @BeforeEach
    @SuppressWarnings("unchecked")
    void prepareClients() {
        provider = mock(ObjectProvider.class);
        model = mock(ChatModel.class);
        client = mock(McpClient.class);
        factory = mock(McpClientFactory.class);
        when(provider.getIfAvailable()).thenReturn(model);
        when(factory.createClient()).thenReturn(client);
        when(client.listTools()).thenReturn(List.of(ToolSpecification.builder().name("ping").build()));
        when(client.executeTool(any(ToolExecutionRequest.class)))
                .thenReturn(ToolExecutionResult.builder().resultText(
                        "{\"service\":\"phoenix-mcp-server\",\"version\":\"test\",\"status\":\"PONG\"}").build());
    }

    /**
     * 即使远程出现新工具，也不会自动提供给模型。
     */
    @Test
    void shouldOnlyAdvertiseAllowedTool() throws Exception {
        when(client.listTools()).thenReturn(List.of(ToolSpecification.builder().name("ping").build(),
                ToolSpecification.builder().name("restart_app").build()));
        when(model.chat(any(ChatRequest.class))).thenReturn(ChatResponse.builder().aiMessage(AiMessage.from("你好")).build());

        service(new ChatProperties(3, 3)).chat("你好");

        var captor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(model).chat(captor.capture());
        assertThat(captor.getValue().parameters().toolSpecifications()).extracting(ToolSpecification::name).containsExactly("ping");
        verify(client).close();
    }

    /**
     * 轮数预算独立于调用次数预算生效。
     */
    @Test
    void shouldEnforceRoundBudgetIndependently() throws Exception {
        when(model.chat(any(ChatRequest.class))).thenReturn(toolResponse("first"), toolResponse("second"));

        assertThatThrownBy(() -> service(new ChatProperties(1, 10)).chat("检查"))
                .isInstanceOfSatisfying(ChatExecutionException.class,
                        exception -> assertThat(exception.code()).isEqualTo("TOOL_BUDGET_EXCEEDED"));

        verify(client, times(1)).executeTool(any(ToolExecutionRequest.class));
        verify(client).close();
    }

    /**
     * 调用次数预算在剩余轮数很多时也会生效。
     */
    @Test
    void shouldEnforceCallBudgetIndependently() throws Exception {
        when(model.chat(any(ChatRequest.class))).thenReturn(toolResponse("first"), toolResponse("second"));

        assertThatThrownBy(() -> service(new ChatProperties(8, 1)).chat("检查"))
                .isInstanceOfSatisfying(ChatExecutionException.class,
                        exception -> assertThat(exception.code()).isEqualTo("TOOL_BUDGET_EXCEEDED"));

        verify(client, times(1)).executeTool(any(ToolExecutionRequest.class));
    }

    /**
     * 无效工具内容不能被当成成功证据或回传给模型。
     */
    @ParameterizedTest
    @ValueSource(strings = {"broken-json", "null", "{}",
            "{\"service\":\"other\",\"version\":\"test\",\"status\":\"PONG\"}"})
    void shouldRejectInvalidToolResult(String result) throws Exception {
        when(model.chat(any(ChatRequest.class))).thenReturn(toolResponse("first"));
        when(client.executeTool(any(ToolExecutionRequest.class)))
                .thenReturn(ToolExecutionResult.builder().resultText(result).build());

        assertThatThrownBy(() -> service(new ChatProperties(3, 3)).chat("检查"))
                .isInstanceOfSatisfying(ChatExecutionException.class, exception -> {
                    assertThat(exception.code()).isEqualTo("MCP_TOOL_FAILED");
                    assertThat(exception.toolCalls().getFirst().status()).isEqualTo("FAILED");
                });

        verify(model, times(1)).chat(any(ChatRequest.class));
        verify(client).close();
    }

    /**
     * 标记为错误的工具结果不能因文本像成功结果而被采信。
     */
    @Test
    void shouldRejectToolErrorFlag() {
        when(model.chat(any(ChatRequest.class))).thenReturn(toolResponse("first"));
        when(client.executeTool(any(ToolExecutionRequest.class)))
                .thenReturn(ToolExecutionResult.builder().isError(true).resultText("tool error").build());

        assertThatThrownBy(() -> service(new ChatProperties(3, 3)).chat("检查"))
                .isInstanceOfSatisfying(ChatExecutionException.class,
                        exception -> assertThat(exception.code()).isEqualTo("MCP_TOOL_FAILED"));
    }

    /**
     * 重复调用 ID 不会破坏消息关联，也不会重复执行第二次工具。
     */
    @Test
    void shouldRejectDuplicateCallId() {
        when(model.chat(any(ChatRequest.class))).thenReturn(toolResponse("same"), toolResponse("same"));

        assertThatThrownBy(() -> service(new ChatProperties(3, 3)).chat("检查"))
                .isInstanceOfSatisfying(ChatExecutionException.class, exception -> {
                    assertThat(exception.code()).isEqualTo("INVALID_TOOL_REQUEST");
                    assertThat(exception.toolCalls()).hasSize(2);
                    assertThat(exception.toolCalls().getLast().status()).isEqualTo("BLOCKED");
                });

        verify(client, times(1)).executeTool(any(ToolExecutionRequest.class));
    }

    /**
     * 非对象或带尾随 JSON 的参数不能绕过空对象约束。
     */
    @ParameterizedTest
    @ValueSource(strings = {"null", "[]", "{} {}"})
    void shouldRejectMalformedArguments(String arguments) {
        var request = ToolExecutionRequest.builder().id("first").name("ping").arguments(arguments).build();
        when(model.chat(any(ChatRequest.class))).thenReturn(ChatResponse.builder()
                .aiMessage(AiMessage.builder().toolExecutionRequests(List.of(request)).build()).build());

        assertThatThrownBy(() -> service(new ChatProperties(3, 3)).chat("检查"))
                .isInstanceOfSatisfying(ChatExecutionException.class,
                        exception -> assertThat(exception.code()).isEqualTo("INVALID_TOOL_REQUEST"));

        verify(client, times(0)).executeTool(any(ToolExecutionRequest.class));
    }

    /**
     * 发现不到允许的工具时返回明确错误，并关闭已经打开的会话。
     */
    @Test
    void shouldHandleMissingTool() throws Exception {
        when(client.listTools()).thenReturn(List.of());

        assertThatThrownBy(() -> service(new ChatProperties(3, 3)).chat("检查"))
                .isInstanceOfSatisfying(ChatExecutionException.class,
                        exception -> assertThat(exception.code()).isEqualTo("PING_TOOL_UNAVAILABLE"));

        verify(client).close();
    }

    /**
     * 工具服务连接失败时不泄露内部异常信息。
     */
    @Test
    void shouldSanitizeMcpConnectionFailure() {
        when(factory.createClient()).thenThrow(new IllegalStateException("internal-sensitive-detail"));

        assertThatThrownBy(() -> service(new ChatProperties(3, 3)).chat("检查"))
                .isInstanceOfSatisfying(ChatExecutionException.class, exception -> {
                    assertThat(exception.code()).isEqualTo("MCP_UNAVAILABLE");
                    assertThat(exception.getMessage()).doesNotContain("internal-sensitive-detail");
                });
    }

    /**
     * 构造待测服务，不创建额外模型或共享会话状态。
     */
    private ChatServiceImpl service(ChatProperties properties) {
        return new ChatServiceImpl(provider, factory, properties, new ObjectMapper());
    }

    /**
     * 生成一个模型提出 ping 调用的响应。
     */
    private ChatResponse toolResponse(String id) {
        var request = ToolExecutionRequest.builder().id(id).name("ping").arguments("{}").build();
        return ChatResponse.builder().aiMessage(AiMessage.builder().toolExecutionRequests(List.of(request)).build()).build();
    }
}
