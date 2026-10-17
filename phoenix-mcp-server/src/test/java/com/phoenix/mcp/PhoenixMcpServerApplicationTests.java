package com.phoenix.mcp;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证目标机工具服务无需 Docker 访问权限即可启动骨架。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PhoenixMcpServerApplicationTests {

    @Autowired
    private TestRestTemplate restTemplate;

    /**
     * 检查工具服务的真实 HTTP 健康接口。
     */
    @Test
    void shouldExposeHealthWithoutDockerAccess() {
        var response = restTemplate.getForEntity("/actuator/health", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo("{\"status\":\"UP\"}");
    }

    /**
     * 明确初始化骨架尚未暴露 MCP 协议端点。
     */
    @Test
    void shouldNotPretendMcpEndpointIsImplemented() {
        var response = restTemplate.getForEntity("/mcp", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
