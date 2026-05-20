package com.kesf.backend.service.qa;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.kesf.backend.config.QaProperties;
import com.kesf.backend.dto.qa.QaChatMessageDTO;
import com.kesf.backend.dto.qa.QaCitationDTO;
import com.kesf.backend.dto.qa.QaSessionResponse;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.ZSetOperations;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class QaSessionServiceTests {

    @Test
    void recordExchangeDoesNotPersistCitationsInUiHistory() throws Exception {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        ZSetOperations<String, String> zSetOperations = mock(ZSetOperations.class);
        ChatMemoryStore chatMemoryStore = mock(ChatMemoryStore.class);
        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);

        QaSessionResponse session = QaSessionResponse.builder()
                .sessionId("session-1")
                .title("问答")
                .createdAt(OffsetDateTime.now())
                .updatedAt(OffsetDateTime.now())
                .build();

        when(valueOperations.get("qa:session:session-1:meta"))
                .thenReturn(objectMapper.writeValueAsString(session));
        when(valueOperations.get("qa:session:session-1:ui-messages")).thenReturn("[]");

        QaSessionService service = new QaSessionService(
                redisTemplate,
                objectMapper,
                new QaProperties(),
                chatMemoryStore
        );
        QaCitationDTO citation = QaCitationDTO.builder()
                .citationId("1")
                .title("Paper")
                .paperMd5("md5")
                .build();

        service.recordExchange("session-1", "问答", "问题", "回答 [1]", List.of(citation));

        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(valueOperations).set(
                eq("qa:session:session-1:ui-messages"),
                jsonCaptor.capture(),
                any(Duration.class)
        );

        List<QaChatMessageDTO> messages = objectMapper.readValue(
                jsonCaptor.getValue(),
                new TypeReference<>() {
                }
        );

        assertThat(messages).hasSize(2);
        assertThat(messages.get(1).getRole()).isEqualTo("assistant");
        assertThat(messages.get(1).getContent()).isEqualTo("回答 [1]");
        assertThat(messages.get(1).getCitations()).isEmpty();
    }
}
