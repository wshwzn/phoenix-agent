package io.github.phoenixagent.mcp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 目标机工具服务的启动入口，MCP 协议与工具将在后续接入。
 */
@SpringBootApplication
public class PhoenixMcpServerApplication {

    /**
     * 启动目标机工具服务骨架。
     */
    public static void main(String[] args) {
        SpringApplication.run(PhoenixMcpServerApplication.class, args);
    }
}
