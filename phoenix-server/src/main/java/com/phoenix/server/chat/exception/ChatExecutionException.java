package com.phoenix.server.chat.exception;

import com.phoenix.server.chat.dto.ToolCallTrace;
import org.springframework.http.HttpStatus;

import java.util.List;

/**
 * 携带安全提示和已完成工具记录，失败也不掩盖此前的实际执行。
 */
public class ChatExecutionException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final String requestId;
    private final List<ToolCallTrace> toolCalls;

    /**
     * 保存对外错误信息和工具快照，不把底层服务异常发送给客户端。
     */
    public ChatExecutionException(HttpStatus status, String code, String message, String requestId,
                                   List<ToolCallTrace> toolCalls) {
        super(message);
        this.status = status;
        this.code = code;
        this.requestId = requestId;
        this.toolCalls = List.copyOf(toolCalls);
    }

    /**
     * 获取对外 HTTP 状态。
     */
    public HttpStatus status() {
        return status;
    }

    /**
     * 获取可供调用方判断的错误代码。
     */
    public String code() {
        return code;
    }

    /**
     * 获取本次请求关联标识。
     */
    public String requestId() {
        return requestId;
    }

    /**
     * 获取失败前已完成或已阻止的工具请求快照。
     */
    public List<ToolCallTrace> toolCalls() {
        return toolCalls;
    }
}
