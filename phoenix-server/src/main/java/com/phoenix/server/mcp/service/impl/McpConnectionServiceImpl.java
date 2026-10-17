package com.phoenix.server.mcp.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.phoenix.server.mcp.config.McpClientFactory;
import com.phoenix.server.mcp.dto.McpConnectionResult;
import com.phoenix.server.mcp.dto.McpPingResult;
import com.phoenix.server.mcp.exception.McpConnectionException;
import com.phoenix.server.mcp.service.McpConnectionService;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import org.springframework.stereotype.Service;

/**
 * 通过真实 MCP 客户端执行探测，每次检查完成后关闭短会话。
 */
@Service
public class McpConnectionServiceImpl implements McpConnectionService {

    private final McpClientFactory clientFactory;
    private final ObjectMapper objectMapper;

    /**
     * 注入客户端工厂与 JSON 解析器。
     */
    public McpConnectionServiceImpl(McpClientFactory clientFactory, ObjectMapper objectMapper) {
        this.clientFactory = clientFactory;
        this.objectMapper = objectMapper;
    }

    /**
     * 完成工具发现和 ping 调用，并校验工具返回的服务身份与状态。
     */
    @Override
    public McpConnectionResult checkConnection() {
        try (var client = clientFactory.createClient()) {
            var tools = client.listTools().stream().map(ToolSpecification::name).toList();
            if (!tools.contains("ping")) {
                throw new IllegalStateException("远程服务没有提供 ping 工具");
            }
            var result = client.executeTool(ToolExecutionRequest.builder()
                    .name("ping")
                    .arguments("{}")
                    .build());
            if (result.isError()) {
                throw new IllegalStateException("远程 ping 工具返回执行错误");
            }
            var ping = objectMapper.readValue(result.resultText(), McpPingResult.class);
            if (ping == null || !"phoenix-mcp-server".equals(ping.service())
                    || !"PONG".equals(ping.status()) || ping.version() == null || ping.version().isBlank()) {
                throw new IllegalStateException("远程 ping 工具返回了非预期结果");
            }
            return new McpConnectionResult("STREAMABLE_HTTP", tools, ping);
        } catch (Exception exception) {
            throw new McpConnectionException(exception);
        }
    }
}
