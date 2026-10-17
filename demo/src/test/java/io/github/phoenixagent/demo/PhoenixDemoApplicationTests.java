package io.github.phoenixagent.demo;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证最小目标应用可以独立提供 HTTP 接口。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PhoenixDemoApplicationTests {

    @Autowired
    private TestRestTemplate restTemplate;

    /**
     * 检查目标应用的健康接口。
     */
    @Test
    void shouldExposeHealthWithoutRedis() {
        var response = restTemplate.getForEntity("/actuator/health", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo("{\"status\":\"UP\"}");
    }

    /**
     * 检查示例接口返回目标应用身份和中文探活结果。
     */
    @Test
    void shouldRespondToDemoPing() {
        var response = restTemplate.getForEntity("/api/demo/ping", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("phoenix-demo", "目标应用运行正常");
    }
}
