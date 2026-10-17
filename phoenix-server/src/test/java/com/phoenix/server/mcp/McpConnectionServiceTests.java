package com.phoenix.server.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.phoenix.server.mcp.config.McpClientFactory;
import com.phoenix.server.mcp.exception.McpConnectionException;
import com.phoenix.server.mcp.service.impl.McpConnectionServiceImpl;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.service.tool.ToolExecutionResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 验证不可信工具结果不能被误报为连通成功，异常时也要关闭会话。
 */
class McpConnectionServiceTests {

    /**
     * 没有发现 ping 时立即失败，不执行其他工具。
     */
    @Test
    void shouldRejectMissingPingAndCloseClient() throws Exception {
        var client = mock(McpClient.class);
        var factory = mock(McpClientFactory.class);
        when(factory.createClient()).thenReturn(client);
        when(client.listTools()).thenReturn(List.of());
        var service = new McpConnectionServiceImpl(factory, new ObjectMapper());

        assertThatThrownBy(service::checkConnection).isInstanceOf(McpConnectionException.class);

        verify(client, never()).executeTool(any(ToolExecutionRequest.class));
        verify(client).close();
    }

    /**
     * JSON 无法解析、身份错误或状态异常时不得返回成功。
     */
    @ParameterizedTest
    @ValueSource(strings = {"not-json", "null", "{}",
            "{\"service\":\"other\",\"version\":\"1\",\"status\":\"PONG\"}",
            "{\"service\":\"phoenix-mcp-server\",\"version\":\"1\",\"status\":\"FAILED\"}",
            "{\"service\":\"phoenix-mcp-server\",\"version\":\"\",\"status\":\"PONG\"}"})
    void shouldRejectInvalidPingResultAndCloseClient(String text) throws Exception {
        var client = mock(McpClient.class);
        var factory = mock(McpClientFactory.class);
        when(factory.createClient()).thenReturn(client);
        when(client.listTools()).thenReturn(List.of(ToolSpecification.builder().name("ping").build()));
        when(client.executeTool(any(ToolExecutionRequest.class)))
                .thenReturn(ToolExecutionResult.builder().resultText(text).build());
        var service = new McpConnectionServiceImpl(factory, new ObjectMapper());

        assertThatThrownBy(service::checkConnection).isInstanceOf(McpConnectionException.class);

        verify(client).close();
    }

    /**
     * 工具明确返回错误时，即使内容像成功结果也不采信。
     */
    @Test
    void shouldRejectToolErrorAndCloseClient() throws Exception {
        var client = mock(McpClient.class);
        var factory = mock(McpClientFactory.class);
        when(factory.createClient()).thenReturn(client);
        when(client.listTools()).thenReturn(List.of(ToolSpecification.builder().name("ping").build()));
        when(client.executeTool(any(ToolExecutionRequest.class)))
                .thenReturn(ToolExecutionResult.builder().isError(true).resultText("tool error").build());
        var service = new McpConnectionServiceImpl(factory, new ObjectMapper());

        assertThatThrownBy(service::checkConnection).isInstanceOf(McpConnectionException.class);

        verify(client).close();
    }
}
