package com.phoenix.server.chat;

import com.phoenix.server.chat.config.ChatProperties;
import com.phoenix.server.chat.config.ModelProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 验证本地模型配置、密钥输出保护和请求预算。
 */
class ModelPropertiesTests {

    /**
     * 未启用时允许不填写模型连接字段。
     */
    @Test
    void shouldAllowEmptySettingsWhenDisabled() {
        var properties = new ModelProperties(false, "", "", "", Duration.ofSeconds(30), 2048);

        assertThat(properties.enabled()).isFalse();
    }

    /**
     * 打印配置对象时不能暴露密钥、地址或模型名称。
     */
    @Test
    void shouldRedactConfigurationToString() {
        var properties = new ModelProperties(true, "https://model.example/v1", "test-model",
                "test-sensitive-marker", Duration.ofSeconds(30), 2048);

        assertThat(properties.toString()).doesNotContain("test-sensitive-marker", "model.example", "test-model");
    }

    /**
     * 启用模型时必须显式提供密钥，不使用默认真实厂商配置。
     */
    @Test
    void shouldRejectMissingEnabledCredentials() {
        assertThatThrownBy(() -> new ModelProperties(true, "https://model.example/v1", "test-model", "",
                Duration.ofSeconds(30), 2048)).isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * 地址必须为无凭据的 API 根路径，不能重复填写 chat/completions。
     */
    @ParameterizedTest
    @ValueSource(strings = {"file:///tmp/model", "http://user:test@localhost/v1", "http://localhost/v1?key=test",
            "http://localhost/v1#fragment", "http://localhost/v1/chat/completions", "not a url"})
    void shouldRejectInvalidBaseUrl(String baseUrl) {
        assertThatThrownBy(() -> new ModelProperties(true, baseUrl, "test-model", "test-placeholder",
                Duration.ofSeconds(30), 2048)).isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * 不允许零值、负值或过大的模型请求超时。
     */
    @ParameterizedTest
    @ValueSource(ints = {-1, 0, 121})
    void shouldRejectInvalidTimeout(int seconds) {
        assertThatThrownBy(() -> new ModelProperties(false, "", "", "", Duration.ofSeconds(seconds), 2048))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * 工具轮数预算必须处于允许范围。
     */
    @ParameterizedTest
    @ValueSource(ints = {0, 9})
    void shouldRejectInvalidRoundBudget(int rounds) {
        assertThatThrownBy(() -> new ChatProperties(rounds, 3)).isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * 同轮多调用也必须受独立调用次数预算约束。
     */
    @ParameterizedTest
    @ValueSource(ints = {0, 11})
    void shouldRejectInvalidCallBudget(int calls) {
        assertThatThrownBy(() -> new ChatProperties(3, calls)).isInstanceOf(IllegalArgumentException.class);
    }
}
