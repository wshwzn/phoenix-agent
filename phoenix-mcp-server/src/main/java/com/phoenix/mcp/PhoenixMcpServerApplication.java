package com.phoenix.mcp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 提供 MCP 协议与连通性测试工具的目标机服务启动入口。
 */
@SpringBootApplication
public class PhoenixMcpServerApplication {

    /**
     * 启动目标机 MCP 工具服务。
     */
    public static void main(String[] args) {
        SpringApplication.run(PhoenixMcpServerApplication.class, args);
    }
}
