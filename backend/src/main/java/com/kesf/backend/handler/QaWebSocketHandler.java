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

/**
 * QA 问答 WebSocket 处理器。
 * <p>
 * 负责处理前端与后端之间为了实现“打字机式”流式问答而建立的长连接通信。
 * 继承自 TextWebSocketHandler，因为当前问答业务只需要处理纯文本（JSON）消息。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class QaWebSocketHandler extends TextWebSocketHandler {

    // QA 核心业务服务，负责调度大模型和知识库检索
    private final QaAgentService qaAgentService;
    // JSON 序列化/反序列化工具
    private final ObjectMapper objectMapper;
    // 在内存中维护当前所有活跃的 WebSocket 会话，用于管理连接生命周期
    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();

    /**
     * 连接成功建立后的回调方法。
     */
    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        // 将新建立的会话保存到内存集合中
        sessions.put(session.getId(), session);
        // 主动向前端下发一条 'connection' 类型的消息，告知握手并连接成功，附带分配的 session ID
        send(session, QaWebSocketResponse.connection(Map.of(
                "webSocketSessionId", session.getId(),
                "message", "QA WebSocket connected"
        )));
    }

    /**
     * 接收并处理前端发来的文本消息的核心方法。
     */
    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        try {
            // 1. 将前端传来的 JSON 字符串反序列化为请求对象
            QaChatStreamRequest request = objectMapper.readValue(message.getPayload(), QaChatStreamRequest.class);
            // 2. 基础的参数校验：提问内容不能为空
            if (!StringUtils.hasText(request.getMessage())) {
                send(session, QaWebSocketResponse.error("message is empty"));
                return;
            }
            // 3. 将请求对象移交给 QaAgentService 处理。
            // 关键点：传入了一个 Consumer (Lambda表达式) 作为回调，每当大模型产出新字符时，就会触发这个 Lambda 向前端发送数据
            qaAgentService.streamAnswer(request, response -> send(session, response));
        } catch (Exception exception) {
            // 捕获顶层异常，防止 WebSocket 连接因为异常而崩溃，并将错误信息反馈给前端
            log.error("QA WebSocket message handling failed, sessionId={}", session.getId(), exception);
            send(session, QaWebSocketResponse.error(exception.getMessage()));
        }
    }

    /**
     * 连接关闭（不论是前端主动关闭还是发生异常中断）后的回调方法。
     */
    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        // 清理内存，防止内存泄漏
        sessions.remove(session.getId());
        log.info("QA WebSocket closed, sessionId={}, status={}", session.getId(), status);
    }

    /**
     * 安全向特定 WebSocket 会话发送消息的内部辅助方法。
     */
    private void send(WebSocketSession session, QaWebSocketResponse response) {
        // 防御性判断：如果连接已经断开，就不再发送
        if (!session.isOpen()) {
            return;
        }
        // 并发控制：Spring 的 WebSocketSession 不是线程安全的，
        // 强并发环境下（例如模型生成过快，多线程回调）可能会报 IllegalStateException，因此这里需要对 session 加锁。
        synchronized (session) {
            try {
                // 序列化响应对象并通过长连接发出
                session.sendMessage(new TextMessage(objectMapper.writeValueAsString(response)));
            } catch (Exception exception) {
                log.error("QA WebSocket send failed, sessionId={}", session.getId(), exception);
            }
        }
    }
}
