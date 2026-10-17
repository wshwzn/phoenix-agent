package com.phoenix.server.chat.service.impl;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;

import java.io.IOException;
import java.time.Instant;
import java.util.Set;

/**
 * 控制端独立的工具授权策略；MCP 描述和模型输出不能自行扩展权限。
 */
public class ReadOnlyToolPolicy {

    public static final Set<String> ALLOWED_TOOLS = Set.of("ping", "inspect_application", "inspect_redis", "read_recent_logs");
    private final ObjectMapper json;

    /**
     * 注入解析器，严格拒绝重复字段与尾随 JSON。
     */
    public ReadOnlyToolPolicy(ObjectMapper json) {
        this.json = json;
    }

    /**
     * 检查封闭参数集合，不允许 URL、文件路径、命令或未知目标。
     */
    public void validateArguments(ToolExecutionRequest request) {
        var node = parse(request.arguments());
        if (!ALLOWED_TOOLS.contains(request.name()) || !node.isObject()) {
            throw new IllegalArgumentException("模型请求了未授权工具或非法参数");
        }
        if ("ping".equals(request.name())) {
            if (!node.isEmpty()) {
                throw new IllegalArgumentException("ping 工具只接受空对象参数");
            }
            return;
        }
        boolean logs = "read_recent_logs".equals(request.name());
        Set<String> fields = logs ? Set.of("targetId", "lines") : Set.of("targetId");
        node.fieldNames().forEachRemaining(field -> {
            if (!fields.contains(field)) {
                throw new IllegalArgumentException("工具包含不允许的参数");
            }
        });
        if (!node.path("targetId").isTextual() || !"demo-local".equals(node.path("targetId").asText())) {
            throw new IllegalArgumentException("目标未登记或不允许访问");
        }
        if (node.has("lines") && (!node.path("lines").isIntegralNumber()
                || !node.path("lines").canConvertToInt() || node.path("lines").intValue() < 1
                || node.path("lines").intValue() > 80)) {
            throw new IllegalArgumentException("日志条数须为 1 到 80 的整数");
        }
    }

    /**
     * 校验结果来源与类型，DOWN 或缺失日志是证据，不是协议执行错误。
     */
    public void validateResult(ToolExecutionRequest request, String text) {
        var node = parse(text);
        if (!node.isObject()) {
            throw new IllegalArgumentException("工具结果不是对象");
        }
        if ("ping".equals(request.name())) {
            if (!"phoenix-mcp-server".equals(node.path("service").asText())
                    || !"PONG".equals(node.path("status").asText()) || node.path("version").asText().isBlank()) {
                throw new IllegalArgumentException("ping 返回了无效结果");
            }
            return;
        }
        String kind = switch (request.name()) {
            case "inspect_application" -> "application";
            case "inspect_redis" -> "redis";
            case "read_recent_logs" -> "logs";
            default -> throw new IllegalArgumentException("未授权工具结果");
        };
        if (!"demo-local".equals(node.path("targetId").asText()) || !kind.equals(node.path("kind").asText())) {
            throw new IllegalArgumentException("工具结果目标或来源不匹配");
        }
        Instant.parse(node.path("observedAt").asText());
        String status = node.path("status").asText();
        boolean valid = switch (kind) {
            case "application" -> validApplication(node);
            case "redis" -> Set.of("UP", "DOWN").contains(status)
                    && Set.of("PONG", "AUTH_REQUIRED", "UNEXPECTED_RESPONSE", "TIMEOUT", "CONNECTION_REFUSED", "CONNECTION_FAILED")
                            .contains(node.path("code").asText())
                    && ("UP".equals(status) == "PONG".equals(node.path("code").asText()));
            case "logs" -> validLogs(node);
            default -> false;
        };
        if (!valid) {
            throw new IllegalArgumentException("工具结果结构或状态无效");
        }
    }

    /**
     * 要求三项探测完整且互不重复，汇总状态须与探活及业务结果一致。
     */
    private boolean validApplication(JsonNode node) {
        var probes = node.path("probes");
        if (!probes.isArray() || probes.size() != 3) {
            return false;
        }
        var names = new java.util.HashSet<String>();
        boolean alive = false;
        boolean allUp = true;
        for (var probe : probes) {
            String name = probe.path("name").asText();
            String status = probe.path("status").asText();
            if (!Set.of("liveness", "health", "product").contains(name) || !names.add(name)
                    || !Set.of("UP", "DOWN").contains(status) || probe.path("code").asText().isBlank()
                    || !probe.path("httpStatus").isIntegralNumber() || probe.path("httpStatus").asInt(-1) < 0
                    || probe.path("httpStatus").asInt(600) > 599) {
                return false;
            }
            alive |= "liveness".equals(name) && "UP".equals(status);
            allUp &= "UP".equals(status);
        }
        return (allUp ? "UP" : alive ? "DEGRADED" : "DOWN").equals(node.path("status").asText());
    }

    /**
     * 日志必须为有限字符串列表，缺失和不可读取状态须带明确代码。
     */
    private boolean validLogs(JsonNode node) {
        String status = node.path("status").asText();
        var lines = node.path("lines");
        if (!Set.of("AVAILABLE", "MISSING", "UNAVAILABLE").contains(status) || !lines.isArray() || lines.size() > 80) {
            return false;
        }
        int characters = 0;
        for (var line : lines) {
            if (!line.isTextual()) {
                return false;
            }
            characters += line.textValue().length() + 1;
        }
        return characters <= 6080 && ("AVAILABLE".equals(status) || lines.isEmpty()
                && Set.of("LOG_NOT_FOUND", "LOG_NOT_REGULAR_FILE", "LOG_READ_FAILED").contains(node.path("code").asText()));
    }

    /**
     * 使用固定错误描述解析 JSON，不把工具原文或模型参数作为异常输出。
     */
    private JsonNode parse(String text) {
        if (text == null) {
            throw new IllegalArgumentException("工具 JSON 缺失");
        }
        try {
            var node = json.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).readTree(text);
            if (node == null) {
                throw new IllegalArgumentException("工具 JSON 缺失");
            }
            return node;
        } catch (IOException exception) {
            throw new IllegalArgumentException("工具 JSON 无效");
        }
    }
}
