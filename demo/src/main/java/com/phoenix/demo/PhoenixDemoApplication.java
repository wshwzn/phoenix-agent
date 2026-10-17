package com.phoenix.demo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 用于后续制造和验证故障的最小目标应用。
 */
@SpringBootApplication
public class PhoenixDemoApplication {

    /**
     * 启动最小目标应用。
     */
    public static void main(String[] args) {
        SpringApplication.run(PhoenixDemoApplication.class, args);
    }
}
