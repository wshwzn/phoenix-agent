package io.github.phoenixagent.demo.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 提供不依赖外部组件的目标应用连通性检查。
 */
@RestController
@RequestMapping("/api/demo")
public class DemoController {

    /**
     * 返回固定探活结果，供初始化阶段验证 HTTP 请求链路。
     */
    @GetMapping("/ping")
    public Map<String, String> ping() {
        return Map.of("application", "phoenix-demo", "message", "目标应用运行正常");
    }
}
