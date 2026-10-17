package com.phoenix.server.chat.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;

/**
 * 单个 OpenAI 兼容模型的本地配置，不通过接口接收或返回密钥。
 */
@ConfigurationProperties(prefix = "phoenix.model")
public record ModelProperties(boolean enabled, String baseUrl, String modelName, String apiKey,
                              Duration requestTimeout, int maxOutputTokens) {

    /**
     * 校验预算和启用后的必要配置，错误提示不包含实际配置值。
     */
    public ModelProperties {
        if (requestTimeout == null || requestTimeout.isZero() || requestTimeout.isNegative()
                || requestTimeout.compareTo(Duration.ofSeconds(120)) > 0) {
            throw new IllegalArgumentException("模型请求超时必须大于 0 且不超过 120 秒");
        }
        if (maxOutputTokens < 128 || maxOutputTokens > 8192) {
            throw new IllegalArgumentException("模型最大输出 token 数须在 128 到 8192 之间");
        }
        if (enabled) {
            if (baseUrl == null || baseUrl.isBlank() || modelName == null || modelName.isBlank()
                    || apiKey == null || apiKey.isBlank()) {
                throw new IllegalArgumentException("启用模型时必须填写 base-url、model-name 和 api-key");
            }
            URI endpoint;
            try {
                endpoint = URI.create(baseUrl);
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("模型 base-url 格式不合法");
            }
            if ((!"http".equalsIgnoreCase(endpoint.getScheme())
                    && !"https".equalsIgnoreCase(endpoint.getScheme()))
                    || endpoint.getHost() == null || endpoint.getUserInfo() != null
                    || endpoint.getQuery() != null || endpoint.getFragment() != null
                    || endpoint.getPath().replaceAll("/+$", "").endsWith("/chat/completions")) {
                throw new IllegalArgumentException("模型 base-url 应为不含凭据和查询参数的 HTTP(S) API 根地址，不含 chat/completions");
            }
        }
    }

    /**
     * 避免配置对象意外被记录时泄露密钥或地址。
     */
    @Override
    public String toString() {
        return "ModelProperties[enabled=" + enabled + ", configuration=<redacted>]";
    }
}
