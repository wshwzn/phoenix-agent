package com.phoenix.server;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证控制端在没有模型和数据库配置时也能独立启动。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PhoenixServerApplicationTests {

    @Autowired
    private TestRestTemplate restTemplate;

    /**
     * 检查真实 HTTP 健康接口可用，且不暴露内部组件信息。
     */
    @Test
    void shouldExposeHealthWithoutExternalServices() {
        var response = restTemplate.getForEntity("/actuator/health", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo("{\"status\":\"UP\"}");
    }
}
