package com.kesf.backend.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kesf.backend.dto.qa.QaChatStreamRequest;
import com.kesf.backend.dto.qa.QaWebSocketResponse;
import com.kesf.backend.service.qa.QaAgentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.util.StringUtils;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
@RequiredArgsConstructor
public class QaWebSocketHandler extends TextWebSocketHandler {

    private final QaAgentService qaAgentService;
    private final ObjectMapper objectMapper;
    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        sessions.put(session.getId(), session);
        send(session, QaWebSocketResponse.connection(Map.of(
                "webSocketSessionId", session.getId(),
                "message", "QA WebSocket connected"
        )));
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        try {
            QaChatStreamRequest request = objectMapper.readValue(message.getPayload(), QaChatStreamRequest.class);
            if (!StringUtils.hasText(request.getMessage())) {
                send(session, QaWebSocketResponse.error("message is empty"));
                return;
            }
            if (!StringUtils.hasText(request.getSessionId())) {
                log.warn("QA WebSocket request rejected because sessionId is empty, webSocketSessionId={}",
                        session.getId());
                send(session, QaWebSocketResponse.error("sessionId is empty, please create QA session first"));
                return;
            }
            qaAgentService.streamAnswer(request, response -> send(session, response));
        } catch (Exception exception) {
            log.error("QA WebSocket message handling failed, sessionId={}", session.getId(), exception);
            send(session, QaWebSocketResponse.error(exception.getMessage()));
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.remove(session.getId());
        log.info("QA WebSocket closed, sessionId={}, status={}", session.getId(), status);
    }

    private void send(WebSocketSession session, QaWebSocketResponse response) {
        if (!session.isOpen()) {
            return;
        }
        synchronized (session) {
            try {
                session.sendMessage(new TextMessage(objectMapper.writeValueAsString(response)));
            } catch (Exception exception) {
                log.error("QA WebSocket send failed, sessionId={}", session.getId(), exception);
            }
        }
    }
}
