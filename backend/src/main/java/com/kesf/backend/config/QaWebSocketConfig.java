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

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(qaWebSocketHandler, "/api/qa/ws")
                .setAllowedOriginPatterns("*");
    }
}
