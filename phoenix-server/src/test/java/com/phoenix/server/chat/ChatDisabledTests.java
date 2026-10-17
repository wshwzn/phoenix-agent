package com.phoenix.server.chat;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 默认未启用模型时保持应用可启动，同时校验 HTTP 输入边界。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "phoenix.model.enabled=false")
class ChatDisabledTests {

    @Autowired
    private TestRestTemplate restTemplate;

    /**
     * 没有配置模型时返回 503 和明确代码，不访问外部服务。
     */
    @Test
    void shouldExplainDisabledModelWithoutBreakingHealth() {
        var response = restTemplate.postForEntity("/api/chat", Map.of("message", "你好"), JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody().path("code").asText()).isEqualTo("MODEL_NOT_CONFIGURED");
        assertThat(response.getBody().path("toolCalls").size()).isZero();
        assertThat(restTemplate.getForEntity("/actuator/health", JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    /**
     * 缺失、空白消息与格式错误在进入模型之前被拒绝。
     */
    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"message\":null}", "{\"message\":\"   \"}", "broken-json"})
    void shouldRejectInvalidBody(String body) {
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        var response = restTemplate.postForEntity("/api/chat", new HttpEntity<>(body, headers), JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().path("code").asText()).isEqualTo("INVALID_MESSAGE");
    }

    /**
     * 单次输入最多接受 4000 个字符。
     */
    @Test
    void shouldRejectOversizedMessage() {
        var response = restTemplate.postForEntity("/api/chat", Map.of("message", "a".repeat(4001)), JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
