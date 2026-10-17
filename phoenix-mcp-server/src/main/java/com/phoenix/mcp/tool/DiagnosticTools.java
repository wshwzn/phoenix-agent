package com.phoenix.mcp.tool;

import com.phoenix.mcp.diagnostic.config.DiagnosticProperties;
import com.phoenix.mcp.diagnostic.service.DiagnosticService;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 将三种固定只读采集暴露为 MCP 工具，直接协议调用也必须通过参数校验。
 */
@Component
public class DiagnosticTools {

    private final DiagnosticService service;

    /**
     * 注入目标采集服务，不注入 Shell 或 Docker 客户端。
     */
    public DiagnosticTools(DiagnosticService service) {
        this.service = service;
    }

    /**
     * 注册限定目标的应用、Redis 和近期日志工具。
     */
    public List<McpServerFeatures.SyncToolSpecification> specifications(McpJsonMapper mapper) {
        return List.of(specification("inspect_application", "检查 demo-local 应用探活、健康和商品接口，区分存活与业务可用", mapper),
                specification("inspect_redis", "对 demo-local 登记的 Redis 执行只读 PING；失败不证明一定是容器停止", mapper),
                specification("read_recent_logs", "读取 demo-local 的近期商品诊断事件；历史错误不代表当前故障，可选 lines 为 1 到 80，默认 40", mapper));
    }

    /**
     * 为每个工具声明封闭参数结构及只读注解。
     */
    private McpServerFeatures.SyncToolSpecification specification(String name, String description, McpJsonMapper mapper) {
        var fields = new LinkedHashMap<String, Object>();
        fields.put("targetId", Map.of("type", "string", "enum", List.of(DiagnosticProperties.TARGET_ID)));
        if ("read_recent_logs".equals(name)) {
            fields.put("lines", Map.of("type", "integer", "minimum", 1, "maximum", 80));
        }
        var tool = McpSchema.Tool.builder().name(name).description(description)
                .inputSchema(new McpSchema.JsonSchema("object", fields, List.of("targetId"), false, null, null))
                .annotations(new McpSchema.ToolAnnotations(description, true, false, true, false, false)).build();
        return McpServerFeatures.SyncToolSpecification.builder().tool(tool)
                .callHandler((exchange, request) -> execute(name, request.arguments(), mapper)).build();
    }

    /**
     * 在真正采集前严格校验目标、字段和整数范围，不依赖模型或 SDK 自动验证。
     */
    private McpSchema.CallToolResult execute(String name, Map<String, Object> arguments, McpJsonMapper mapper) {
        boolean logs = "read_recent_logs".equals(name);
        Set<String> allowed = logs ? Set.of("targetId", "lines") : Set.of("targetId");
        if (arguments == null || !allowed.containsAll(arguments.keySet())
                || !DiagnosticProperties.TARGET_ID.equals(arguments.get("targetId"))) {
            return error("目标未登记或工具参数无效");
        }
        int lines = 40;
        if (arguments.containsKey("lines")) {
            Object value = arguments.get("lines");
            if (!(value instanceof Integer || value instanceof Long)
                    || ((Number) value).longValue() < 1 || ((Number) value).longValue() > 80) {
                return error("日志条数须为 1 到 80 的整数");
            }
            lines = ((Number) value).intValue();
        }
        try {
            var result = switch (name) {
                case "inspect_application" -> service.inspectApplication(DiagnosticProperties.TARGET_ID);
                case "inspect_redis" -> service.inspectRedis(DiagnosticProperties.TARGET_ID);
                case "read_recent_logs" -> service.readRecentLogs(DiagnosticProperties.TARGET_ID, lines);
                default -> throw new IllegalArgumentException("工具未登记");
            };
            return McpSchema.CallToolResult.builder().isError(false).structuredContent(result)
                    .addTextContent(mapper.writeValueAsString(result)).build();
        } catch (RuntimeException | IOException exception) {
            return error("采集未完成，不能据此判断目标健康");
        }
    }

    /**
     * 只返回固定错误信息，不暴露地址、文件路径或内部异常。
     */
    private McpSchema.CallToolResult error(String message) {
        return McpSchema.CallToolResult.builder().isError(true).addTextContent(message).build();
    }
}
