package com.phoenix.server.mcp.controller;

import com.phoenix.server.mcp.dto.McpConnectionResult;
import com.phoenix.server.mcp.exception.McpConnectionException;
import com.phoenix.server.mcp.service.McpConnectionService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 本机联调入口，只能检查已配置的服务和固定 ping 工具。
 */
@RestController
@RequestMapping("/api/mcp")
public class McpConnectionController {

    private final McpConnectionService connectionService;

    /**
     * 注入连通性检查服务。
     */
    public McpConnectionController(McpConnectionService connectionService) {
        this.connectionService = connectionService;
    }

    /**
     * 无需模型和目标应用即可验证 MCP 工具发现与调用。
     */
    @GetMapping("/check")
    public McpConnectionResult checkConnection() {
        return connectionService.checkConnection();
    }

    /**
     * 将连接或协议异常映射为 502，不暴露底层异常内容。
     */
    @ExceptionHandler(McpConnectionException.class)
    public ProblemDetail handleConnectionFailure(McpConnectionException exception) {
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY, exception.getMessage());
        problem.setTitle("MCP 连通性检查失败");
        return problem;
    }
}
