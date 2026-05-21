package com.kesf.backend.service.qa;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kesf.backend.config.QaProperties;
import dev.langchain4j.model.chat.StreamingChatModel;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class QueryTranslationServiceTests {

    @Test
    void rewriteUsesLocalFallbackWithoutCallingModelWhenRewriteIsDisabled() {
        QaProperties qaProperties = new QaProperties();
        qaProperties.setRewriteEnabled(false);
        StreamingChatModel chatModel = mock(StreamingChatModel.class);
        QueryTranslationService service = new QueryTranslationService(new ObjectMapper(), qaProperties);

        QueryTranslationService.QueryRewriteResult result = service.rewrite("Transformer 的 multi-head attention 作用是什么？", chatModel);

        assertThat(result.standaloneQuestionZh()).isEqualTo("Transformer 的 multi-head attention 作用是什么？");
        assertThat(result.queryEn()).contains("Transformer").contains("multi-head");
        assertThat(result.exactTerms()).contains("Transformer", "multi-head", "attention");
        verify(chatModel, never()).chat(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any()
        );
    }
}
