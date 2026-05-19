package com.kesf.backend.config;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageDeserializer;
import dev.langchain4j.data.message.ChatMessageSerializer;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.List;

@Repository
@RequiredArgsConstructor
public class RedisChatMemoryStore implements ChatMemoryStore {

    private static final String KEY_PREFIX = "chat:session:";
    private static final String KEY_SUFFIX = ":messages";

    private final StringRedisTemplate stringRedisTemplate;
    private final QaProperties qaProperties;

    @Override
    public List<ChatMessage> getMessages(Object memoryId) {
        String json = stringRedisTemplate.opsForValue().get(key(memoryId));
        if (!StringUtils.hasText(json)) {
            return List.of();
        }
        return ChatMessageDeserializer.messagesFromJson(json);
    }

    @Override
    public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        String json = ChatMessageSerializer.messagesToJson(messages == null ? List.of() : messages);
        stringRedisTemplate.opsForValue().set(
                key(memoryId),
                json,
                Duration.ofDays(Math.max(1, qaProperties.getRedisSessionTtlDays()))
        );
    }

    @Override
    public void deleteMessages(Object memoryId) {
        stringRedisTemplate.delete(key(memoryId));
    }

    private String key(Object memoryId) {
        return KEY_PREFIX + memoryId + KEY_SUFFIX;
    }
}
