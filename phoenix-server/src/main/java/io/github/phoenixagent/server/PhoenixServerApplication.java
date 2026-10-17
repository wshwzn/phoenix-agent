package io.github.phoenixagent.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Phoenix Agent 控制端的启动入口。
 */
@SpringBootApplication
public class PhoenixServerApplication {

    /**
     * 启动 Agent 控制端。
     */
    public static void main(String[] args) {
        SpringApplication.run(PhoenixServerApplication.class, args);
    }
}
