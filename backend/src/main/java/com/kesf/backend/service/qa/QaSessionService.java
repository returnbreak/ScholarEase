package com.kesf.backend.service.qa;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kesf.backend.config.QaProperties;
import com.kesf.backend.dto.qa.QaChatMessageDTO;
import com.kesf.backend.dto.qa.QaCitationDTO;
import com.kesf.backend.dto.qa.QaSessionDetailResponse;
import com.kesf.backend.dto.qa.QaSessionResponse;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * QA 问答会话管理服务。
 * <p>
 * 负责处理前端聊天界面的会话列表、历史消息的存取。
 * 数据持久化依赖 Redis，支持按时间排序的会话列表检索以及自动过期（TTL）清理。
 * </p>
 */
@Service
@RequiredArgsConstructor
public class QaSessionService {

    // 用于存储所有会话 ID 并按更新时间戳排序的 Redis 有序集合 (ZSet) 键名
    private static final String SESSION_INDEX_KEY = "qa:session:index";
    // 会话元数据（如标题、时间等）的 Redis 键名前缀和后缀
    private static final String SESSION_META_KEY_PREFIX = "qa:session:";
    private static final String SESSION_META_KEY_SUFFIX = ":meta";
    // 会话完整消息列表（用于前端展示 UI）的 Redis 键后缀
    private static final String SESSION_MESSAGES_KEY_SUFFIX = ":ui-messages";
    private static final String DEFAULT_TITLE = "新对话";

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;
    private final QaProperties qaProperties;
    // LangChain4j 的上下文记忆存储，用于在删除会话时同步清理大模型的记忆
    private final ChatMemoryStore chatMemoryStore;

    /**
     * 创建一个新的空白问答会话，并持久化到 Redis。
     */
    public QaSessionResponse createSession() {
        OffsetDateTime now = OffsetDateTime.now();
        QaSessionResponse session = QaSessionResponse.builder()
                .sessionId(UUID.randomUUID().toString())
                .title(DEFAULT_TITLE)
                .createdAt(now)
                .updatedAt(now)
                .build();
        saveSession(session);
        saveMessages(session.getSessionId(), List.of());
        return session;
    }

    /**
     * 获取所有会话的概览列表（不包含具体消息）。
     * 按会话的最后更新时间降序排列。
     */
    public List<QaSessionResponse> listSessions() {
        // 1. 从 Redis ZSet 中获取按分数（时间戳）倒序排列的所有会话 ID
        Set<String> sessionIds = stringRedisTemplate.opsForZSet()
                .reverseRange(SESSION_INDEX_KEY, 0, -1);
        if (sessionIds == null || sessionIds.isEmpty()) {
            return List.of();
        }
        // 2. 遍历 ID 列表，读取具体的会话元数据，并最终按时间再次降序确保顺序正确
        return sessionIds.stream()
                .map(this::readSession)
                .filter(session -> session != null)
                .sorted(Comparator.comparing(QaSessionResponse::getUpdatedAt).reversed())
                .toList();
    }

    /**
     * 根据会话 ID 获取会话的详细信息（包含历史消息列表）。
     * 如果会话不存在，则自动创建一个。
     */
    public QaSessionDetailResponse getSession(String sessionId) {
        QaSessionResponse session = readSession(sessionId);
        if (session == null) {
            // 容错处理：如果前端传来了未知的 ID，或者 Redis 数据已过期，自动作为新会话处理
            session = createSession(sessionId);
        }
        return QaSessionDetailResponse.builder()
                .sessionId(session.getSessionId())
                .title(session.getTitle())
                .createdAt(session.getCreatedAt())
                .updatedAt(session.getUpdatedAt())
                .messages(readMessages(session.getSessionId()))
                .build();
    }

    /**
     * 删除指定的会话。
     * 包括清空其在 Redis 中的元数据、UI 消息列表、索引，以及 LangChain4j 的底层上下文记忆。
     */
    public boolean deleteSession(String sessionId) {
        if (!StringUtils.hasText(sessionId)) {
            return false;
        }
        Long removed = stringRedisTemplate.opsForZSet().remove(SESSION_INDEX_KEY, sessionId);
        Boolean deletedMeta = stringRedisTemplate.delete(metaKey(sessionId));
        Boolean deletedMessages = stringRedisTemplate.delete(messagesKey(sessionId));
        // 同步删除大模型的记忆存储，防止再次使用同 ID 创建会话时出现“记忆串台”
        chatMemoryStore.deleteMessages(sessionId);
        return (removed != null && removed > 0)
                || Boolean.TRUE.equals(deletedMeta)
                || Boolean.TRUE.equals(deletedMessages);
    }

    /**
     * 记录一轮完整的问答交互（包含用户的提问和 AI 的回答及文献引用）。
     * <p>
     * 此方法通常在大模型流式输出彻底结束后被调用，将最新的对话内容追加到历史记录中保存。
     */
    public void recordExchange(String sessionId,
                               String sessionTitle,
                               String userMessage,
                               String assistantMessage,
                               List<QaCitationDTO> citations) {
        QaSessionResponse session = readSession(sessionId);
        if (session == null) {
            session = createSession(sessionId);
        }

        // 将用户问题和 AI 回答作为两条独立的消息追加到现有列表中
        List<QaChatMessageDTO> messages = new ArrayList<>(readMessages(sessionId));
        messages.add(QaChatMessageDTO.builder()
                .id(UUID.randomUUID().toString())
                .role("user")
                .content(userMessage == null ? "" : userMessage)
                .citations(List.of())
                .build());
        messages.add(QaChatMessageDTO.builder()
                .id(UUID.randomUUID().toString())
                .role("assistant")
                .content(assistantMessage == null ? "" : assistantMessage)
                .citations(citations == null ? List.of() : citations)
                .build());

        OffsetDateTime now = OffsetDateTime.now();
        // 标题由前端生成并传入，后端只负责在 Redis 中保存，不再重复根据问题生成标题。
        if (StringUtils.hasText(sessionTitle)
                && (!StringUtils.hasText(session.getTitle()) || DEFAULT_TITLE.equals(session.getTitle()))) {
            session.setTitle(sessionTitle.trim());
        }
        session.setUpdatedAt(now);
        saveSession(session);
        saveMessages(sessionId, messages);
    }

    /**
     * 内部方法：用指定的 ID 创建会话。
     */
    private QaSessionResponse createSession(String sessionId) {
        OffsetDateTime now = OffsetDateTime.now();
        QaSessionResponse session = QaSessionResponse.builder()
                .sessionId(sessionId)
                .title(DEFAULT_TITLE)
                .createdAt(now)
                .updatedAt(now)
                .build();
        saveSession(session);
        saveMessages(sessionId, List.of());
        return session;
    }

    /**
     * 从 Redis 读取并反序列化会话的元数据信息。
     */
    private QaSessionResponse readSession(String sessionId) {
        if (!StringUtils.hasText(sessionId)) {
            return null;
        }
        String json = stringRedisTemplate.opsForValue().get(metaKey(sessionId));
        if (!StringUtils.hasText(json)) {
            return null;
        }
        try {
            return objectMapper.readValue(json, QaSessionResponse.class);
        } catch (JsonProcessingException exception) {
            return null;
        }
    }

    /**
     * 从 Redis 读取并反序列化会话的具体聊天消息列表。
     */
    private List<QaChatMessageDTO> readMessages(String sessionId) {
        String json = stringRedisTemplate.opsForValue().get(messagesKey(sessionId));
        if (!StringUtils.hasText(json)) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<QaChatMessageDTO>>() {
            });
        } catch (JsonProcessingException exception) {
            return List.of();
        }
    }

    /**
     * 将会话元数据保存到 Redis，并更新 ZSet 中的时间戳用于排序。
     */
    private void saveSession(QaSessionResponse session) {
        Duration ttl = sessionTtl();
        try {
            // 1. 保存/覆盖具体的元数据 JSON
            stringRedisTemplate.opsForValue().set(
                    metaKey(session.getSessionId()),
                    objectMapper.writeValueAsString(session),
                    ttl
            );
            // 2. 在 ZSet 中添加或更新该会话的分数（使用毫秒时间戳作为 score 以实现按时间排序）
            stringRedisTemplate.opsForZSet().add(
                    SESSION_INDEX_KEY,
                    session.getSessionId(),
                    session.getUpdatedAt().toInstant().toEpochMilli()
            );
            stringRedisTemplate.expire(SESSION_INDEX_KEY, ttl);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("QA session metadata serialization failed", exception);
        }
    }

    /**
     * 将完整的聊天消息列表序列化保存到 Redis。
     */
    private void saveMessages(String sessionId, List<QaChatMessageDTO> messages) {
        try {
            stringRedisTemplate.opsForValue().set(
                    messagesKey(sessionId),
                    objectMapper.writeValueAsString(messages == null ? List.of() : messages),
                    sessionTtl()
            );
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("QA session message serialization failed", exception);
        }
    }

    /**
     * 获取配置中定义的会话过期时间（TTL）。
     */
    private Duration sessionTtl() {
        return Duration.ofDays(Math.max(1, qaProperties.getRedisSessionTtlDays()));
    }

    /**
     * 拼接 Redis 元数据 Key
     */
    private String metaKey(String sessionId) {
        return SESSION_META_KEY_PREFIX + sessionId + SESSION_META_KEY_SUFFIX;
    }

    /**
     * 拼接 Redis 消息列表 Key
     */
    private String messagesKey(String sessionId) {
        return SESSION_META_KEY_PREFIX + sessionId + SESSION_MESSAGES_KEY_SUFFIX;
    }
}
