package com.phoenix.server.mcp.exception;

/**
 * 表示连接、协议或测试工具结果不符合预期，不等同于目标应用发生故障。
 */
public class McpConnectionException extends RuntimeException {

    /**
     * 保存内部异常原因，对外仅返回不含地址和凭据的提示。
     */
    public McpConnectionException(Throwable cause) {
        super("MCP 连通性检查失败，请检查工具服务是否启动及连接配置是否正确", cause);
    }
}
