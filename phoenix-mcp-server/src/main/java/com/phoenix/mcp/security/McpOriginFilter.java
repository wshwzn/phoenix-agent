package com.phoenix.mcp.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 当前协议端点仅服务后端客户端，拒绝携带 Origin 的浏览器请求。
 */
@Component
public class McpOriginFilter extends OncePerRequestFilter {

    /**
     * 只保护 MCP 协议端点，不影响健康检查与应用信息接口。
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().equals(request.getContextPath() + "/mcp");
    }

    /**
     * 拒绝浏览器来源请求，避免本机服务被跨站网页直接调用。
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (request.getHeader("Origin") != null) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN);
            return;
        }
        filterChain.doFilter(request, response);
    }
}
