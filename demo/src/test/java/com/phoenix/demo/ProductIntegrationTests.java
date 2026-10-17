package com.phoenix.demo;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 用真实业务 HTTP 与 Redis 客户端替身验证商品查询、错误映射和专用日志。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "phoenix.demo.diagnostic-log=target/product-test-diagnostic.log")
class ProductIntegrationTests {

    @Autowired
    private TestRestTemplate rest;
    @MockitoBean
    private StringRedisTemplate redis;
    private ValueOperations<String, String> values;

    /**
     * 每次测试使用独立的 Redis 操作替身，不访问本机 Redis。
     */
    @BeforeEach
    @SuppressWarnings("unchecked")
    void prepareRedis() {
        values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
    }

    /**
     * Redis 无初始数据仍返回固定商品，但必须实际发起 GET。
     */
    @Test
    void shouldQueryProductWithoutSeedingRedis() {
        var response = rest.getForEntity("/api/demo/products/1", JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().path("id").asInt()).isEqualTo(1);
        assertThat(response.getBody().path("name").asText()).isEqualTo("Phoenix 演示商品");
        verify(values).get("phoenix:demo:product:1:name");
    }

    /**
     * 已有商品名称确实来自 Redis，而不是固定成功响应。
     */
    @Test
    void shouldUseValueReadFromRedis() {
        when(values.get("phoenix:demo:product:1:name")).thenReturn("Redis 中的商品名称");
        var response = rest.getForEntity("/api/demo/products/1", JsonNode.class);
        assertThat(response.getBody().path("name").asText()).isEqualTo("Redis 中的商品名称");
    }

    /**
     * 依赖故障返回安全 503，探活与健康仍正常，并留下不含异常原文的诊断事件。
     */
    @Test
    void shouldExposeDependencyFailureWithoutBreakingLiveness() throws Exception {
        when(values.get("phoenix:demo:product:1:name"))
                .thenThrow(new RedisConnectionFailureException("password=secret-fixture internal-host"));
        var response = rest.getForEntity("/api/demo/products/1", JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody().path("code").asText()).isEqualTo("REDIS_UNAVAILABLE");
        assertThat(response.getBody().toString()).doesNotContain("secret-fixture", "internal-host");
        assertThat(rest.getForEntity("/api/demo/ping", String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(rest.getForEntity("/actuator/health", String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        String log = Files.readString(Path.of("target/product-test-diagnostic.log"));
        assertThat(log).contains("event=PRODUCT_QUERY_FAILED", "code=REDIS_UNAVAILABLE")
                .doesNotContain("secret-fixture", "internal-host");
    }

    /**
     * 不提供任意商品参数或业务写接口。
     */
    @Test
    void shouldNotProvideArbitraryProducts() {
        assertThat(rest.getForEntity("/api/demo/products/2", String.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
