package com.phoenix.mcp;

import com.phoenix.mcp.diagnostic.config.DiagnosticProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 验证可信目标配置在启动时校验，路径按文件系统解释。
 */
class DiagnosticPropertiesTests {

    /**
     * 相对日志路径按工作目录解析，不当作 Servlet 资源路径。
     */
    @Test
    void shouldResolveRelativeLogFile() {
        var properties = create("http://127.0.0.1:18082", 16379, Duration.ofMillis(500));
        assertThat(properties.logPath()).isAbsolute();
        assertThat(properties.logPath().getFileName().toString()).isEqualTo("diagnostic.log");
    }

    /**
     * 应用仅接受明确的 HTTP 根地址，不允许凭据、路径、片段或查询参数。
     */
    @ParameterizedTest
    @ValueSource(strings = {"ftp://localhost", "https://localhost", "/relative", "http://user:secret@localhost",
            "http://localhost/private", "http://localhost?key=secret", "http://localhost#fragment"})
    void shouldRejectInvalidApplicationUrl(String url) {
        assertThatThrownBy(() -> create(url, 16379, Duration.ofMillis(500))).isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * 端口上下界被拒绝。
     */
    @ParameterizedTest
    @ValueSource(ints = {0, -1, 65536})
    void shouldRejectInvalidRedisPort(int port) {
        assertThatThrownBy(() -> create("http://localhost", port, Duration.ofMillis(500))).isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * 过短或过长采集超时不能突破约定预算。
     */
    @ParameterizedTest
    @ValueSource(ints = {0, 99, 1001})
    void shouldRejectInvalidTimeout(int milliseconds) {
        assertThatThrownBy(() -> create("http://localhost", 16379, Duration.ofMillis(milliseconds))).isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * 构造不带真实凭据的目标配置。
     */
    private DiagnosticProperties create(String url, int port, Duration timeout) {
        return new DiagnosticProperties(URI.create(url), "127.0.0.1", port, "../demo/logs/diagnostic.log", timeout);
    }
}
