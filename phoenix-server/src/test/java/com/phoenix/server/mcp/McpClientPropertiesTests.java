package com.phoenix.server.mcp;

import com.phoenix.server.mcp.config.McpClientProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 验证连接配置不会接受非 HTTP 地址、URL 凭据或无限等待。
 */
class McpClientPropertiesTests {

    /**
     * 接受普通 HTTP(S) MCP 地址。
     */
    @Test
    void shouldAcceptHttpEndpoint() {
        var properties = new McpClientProperties(URI.create("http://127.0.0.1:18081/mcp"), Duration.ofSeconds(5));

        assertThat(properties.serverUrl().getPath()).isEqualTo("/mcp");
    }

    /**
     * 拒绝不支持的协议及可能混入凭据的地址。
     */
    @ParameterizedTest
    @ValueSource(strings = {"file:///tmp/mcp", "http://user:placeholder@localhost/mcp",
            "http://localhost/mcp?token=placeholder", "http://localhost/mcp#fragment"})
    void shouldRejectUnsafeEndpoint(String endpoint) {
        assertThatThrownBy(() -> new McpClientProperties(URI.create(endpoint), Duration.ofSeconds(5)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * 拒绝零值、负值和超过允许范围的超时。
     */
    @ParameterizedTest
    @ValueSource(ints = {-1, 0, 31})
    void shouldRejectInvalidTimeout(int seconds) {
        assertThatThrownBy(() -> new McpClientProperties(URI.create("http://localhost/mcp"), Duration.ofSeconds(seconds)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
