package com.phoenix.mcp.tool;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * 返回固定服务信息的连通性测试工具，不采集目标机器信息。
 */
@Component
public class PingTool {

    public static final String NAME = "ping";
    public static final String SERVICE_NAME = "phoenix-mcp-server";
    public static final String SERVICE_VERSION = "0.0.1-SNAPSHOT";

    /**
     * 声明无参数、只读的 ping 工具及其调用处理方法。
     */
    public McpServerFeatures.SyncToolSpecification specification(McpJsonMapper jsonMapper) {
        var tool = McpSchema.Tool.builder()
                .name(NAME)
                .description("测试 Phoenix MCP 服务连通性，返回固定服务名称、版本和 PONG；不接受参数")
                .inputSchema(new McpSchema.JsonSchema("object", Map.of(), List.of(), false, null, null))
                .annotations(new McpSchema.ToolAnnotations("连通性测试", true, false, true, false, false))
                .build();

        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(tool)
                .callHandler((exchange, request) -> execute(request, jsonMapper))
                .build();
    }

    /**
     * 校验空参数并同时返回 JSON 文本与结构化结果。
     */
    private McpSchema.CallToolResult execute(McpSchema.CallToolRequest request, McpJsonMapper jsonMapper) {
        if (request.arguments() != null && !request.arguments().isEmpty()) {
            return McpSchema.CallToolResult.builder()
                    .isError(true)
                    .addTextContent("ping 工具不接受参数")
                    .build();
        }

        var result = Map.of("service", SERVICE_NAME, "version", SERVICE_VERSION, "status", "PONG");
        try {
            return McpSchema.CallToolResult.builder()
                    .isError(false)
                    .structuredContent(result)
                    .addTextContent(jsonMapper.writeValueAsString(result))
                    .build();
        } catch (IOException exception) {
            throw new IllegalStateException("无法序列化 ping 工具结果", exception);
        }
    }
}
