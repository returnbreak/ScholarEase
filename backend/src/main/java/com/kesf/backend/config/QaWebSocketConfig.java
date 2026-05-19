package com.kesf.backend.config;

import com.kesf.backend.handler.QaWebSocketHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
@RequiredArgsConstructor
public class QaWebSocketConfig implements WebSocketConfigurer {

    private final QaWebSocketHandler qaWebSocketHandler;

    /**
     * 注册 WebSocket 处理器及其对应的网络端点（URL）。
     * 
     * @param registry WebSocket 处理器注册表，用于配置路由映射和跨域策略
     */
    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // 1. addHandler: 将前端请求的 URL 路径 "/api/qa/ws" 绑定到特定的处理器 `qaWebSocketHandler` 上。
        //    当 Vue 前端发起 `new WebSocket('ws://.../api/qa/ws')` 时，就会由该 handler 接收并处理消息。
        registry.addHandler(qaWebSocketHandler, "/api/qa/ws")
                
                // 2. setAllowedOriginPatterns: 配置跨域资源共享（CORS）。
                //    设置为 "*" 表示允许来自任何域名、端口的客户端连接。
                //    如果不加这一行，前端（如在 localhost:5173 运行的 Vue）连接后端（如 localhost:8080）时会被浏览器作为跨域拦截。
                .setAllowedOriginPatterns("*");
    }
}
