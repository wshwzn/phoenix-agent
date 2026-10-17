package com.phoenix.mcp.diagnostic.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;

/**
 * 运维人员登记的唯一演示目标，地址和路径不由模型输入。
 */
@ConfigurationProperties(prefix = "phoenix.diagnostic")
public record DiagnosticProperties(URI applicationBaseUrl, String redisHost, int redisPort,
                                   String logFile, Duration probeTimeout) {

    public static final String TARGET_ID = "demo-local";

    /**
     * 启动时校验目标与超时，禁止 URL 携带凭据或额外路径。
     */
    public DiagnosticProperties {
        if (applicationBaseUrl == null || applicationBaseUrl.getHost() == null
                || !"http".equals(applicationBaseUrl.getScheme())
                || applicationBaseUrl.getUserInfo() != null || applicationBaseUrl.getQuery() != null
                || applicationBaseUrl.getFragment() != null
                || !(applicationBaseUrl.getPath().isEmpty() || "/".equals(applicationBaseUrl.getPath()))) {
            throw new IllegalArgumentException("演示应用须配置无凭据、无额外路径的 HTTP 根地址");
        }
        if (redisHost == null || !redisHost.matches("[a-zA-Z0-9.:-]{1,253}")
                || redisPort < 1 || redisPort > 65535 || logFile == null || logFile.isBlank()) {
            throw new IllegalArgumentException("演示 Redis 地址、端口或日志路径无效");
        }
        if (probeTimeout == null || probeTimeout.compareTo(Duration.ofMillis(100)) < 0
                || probeTimeout.compareTo(Duration.ofSeconds(1)) > 0) {
            throw new IllegalArgumentException("采集超时须在 100 毫秒到 1 秒之间");
        }
        logFile = Path.of(logFile).toAbsolutePath().normalize().toString();
    }

    /**
     * 将普通文件系统路径转换为 Path，不经过 Servlet 资源路径解释。
     */
    public Path logPath() {
        return Path.of(logFile);
    }
}
