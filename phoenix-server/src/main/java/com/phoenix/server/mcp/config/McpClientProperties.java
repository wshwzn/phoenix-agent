package com.phoenix.server.mcp.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;
import java.util.Objects;

/**
 * 由服务配置提供固定 MCP 地址，检查接口不接受用户传入的 URL。
 */
@ConfigurationProperties(prefix = "phoenix.mcp")
public record McpClientProperties(URI serverUrl, Duration requestTimeout) {

    /**
     * 启动时校验地址和超时，禁止把凭据直接放进 URL。
     */
    public McpClientProperties {
        Objects.requireNonNull(serverUrl, "MCP 服务地址不能为空");
        Objects.requireNonNull(requestTimeout, "MCP 请求超时不能为空");
        if ((!"http".equalsIgnoreCase(serverUrl.getScheme())
                && !"https".equalsIgnoreCase(serverUrl.getScheme()))
                || serverUrl.getHost() == null || serverUrl.getUserInfo() != null
                || serverUrl.getFragment() != null || serverUrl.getQuery() != null) {
            throw new IllegalArgumentException("MCP 地址须为不含凭据、查询参数或片段的 HTTP(S) URL");
        }
        if (requestTimeout.isNegative() || requestTimeout.isZero()
                || requestTimeout.compareTo(Duration.ofSeconds(30)) > 0) {
            throw new IllegalArgumentException("MCP 请求超时必须大于 0 且不超过 30 秒");
        }
    }
}
