package com.phoenix.server.chat.controller;

import com.phoenix.server.chat.dto.ChatRequest;
import com.phoenix.server.chat.dto.ChatResponse;
import com.phoenix.server.chat.exception.ChatExecutionException;
import com.phoenix.server.chat.service.ChatService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 本机非流式单轮对话入口，尚无鉴权、历史记忆或数据库持久化。
 */
@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final ChatService chatService;

    /**
     * 注入对话业务服务。
     */
    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    /**
     * 接收用户文本并返回最终回答与本次工具调用明细。
     */
    @PostMapping
    public ChatResponse chat(@Valid @RequestBody ChatRequest request) {
        return chatService.chat(request.message());
    }

    /**
     * 返回安全的错误代码、请求 ID 和已发生工具调用，不暴露供应商原始异常。
     */
    @ExceptionHandler(ChatExecutionException.class)
    public ProblemDetail handleExecutionFailure(ChatExecutionException exception) {
        var problem = ProblemDetail.forStatusAndDetail(exception.status(), exception.getMessage());
        problem.setTitle("对话执行失败");
        problem.setProperty("code", exception.code());
        problem.setProperty("requestId", exception.requestId());
        problem.setProperty("toolCalls", exception.toolCalls());
        return problem;
    }

    /**
     * 对空消息、超长消息或错误 JSON 返回统一校验提示。
     */
    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class})
    public ProblemDetail handleInvalidRequest(Exception exception) {
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                "请求体应包含 message，内容须为 1 到 4000 字符的非空文本");
        problem.setTitle("对话请求不合法");
        problem.setProperty("code", "INVALID_MESSAGE");
        return problem;
    }
}
