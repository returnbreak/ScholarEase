package com.kesf.backend.service.qa;

import com.kesf.backend.config.QaProperties;
import com.kesf.backend.dto.qa.QaChatStreamRequest;
import com.kesf.backend.dto.qa.QaWebSocketResponse;
import com.kesf.backend.service.PaperRetrievalSearchService;
import com.kesf.backend.service.impl.PaperVectorDocument;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.model.chat.StreamingChatModel;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class QaAgentServiceTests {

    @Test
    void streamAnswerReturnsNoEvidenceWhenRetrievedHitsAreBelowEvidenceThreshold() {
        ChatMemoryProvider chatMemoryProvider = mock(ChatMemoryProvider.class);
        QueryTranslationService queryTranslationService = mock(QueryTranslationService.class);
        PaperRetrievalSearchService retrievalSearchService = mock(PaperRetrievalSearchService.class);
        QaSessionService qaSessionService = mock(QaSessionService.class);
        QaStreamingChatModelFactory chatModelFactory = mock(QaStreamingChatModelFactory.class);
        StreamingChatModel streamingChatModel = mock(StreamingChatModel.class);
        ChatMemory chatMemory = mock(ChatMemory.class);

        QaProperties qaProperties = new QaProperties();
        qaProperties.setMinimumEvidenceScore(0.02d);
        qaProperties.setMinimumEvidenceChunks(1);
        qaProperties.getPrompts().setNoEvidenceMessage("NO_EVIDENCE");

        QaAgentService service = new QaAgentService(
                chatMemoryProvider,
                queryTranslationService,
                retrievalSearchService,
                new CitationAssembler(),
                qaSessionService,
                chatModelFactory,
                qaProperties
        );

        var rewrite = new QueryTranslationService.QueryRewriteResult(
                "中文问题",
                "english query",
                List.of(),
                List.of()
        );
        var retrievalQuery = rewrite.toRetrievalQuery();

        when(chatModelFactory.create(null)).thenReturn(streamingChatModel);
        when(queryTranslationService.rewrite("中文问题", streamingChatModel)).thenReturn(rewrite);
        when(retrievalSearchService.searchHybrid(retrievalQuery)).thenReturn(List.of(hitWithRrfScore(0.01d)));
        when(chatMemoryProvider.get("session-1")).thenReturn(chatMemory);

        List<QaWebSocketResponse> responses = new ArrayList<>();
        QaChatStreamRequest request = new QaChatStreamRequest();
        request.setSessionId("session-1");
        request.setMessage("中文问题");

        service.streamAnswer(request, responses::add);

        assertThat(responses).extracting(QaWebSocketResponse::getType)
                .containsExactly("token", "completion");
        assertThat(responses.get(0).getContent()).isEqualTo("NO_EVIDENCE");
        verify(streamingChatModel, never()).chat(org.mockito.ArgumentMatchers.anyList(),
                org.mockito.ArgumentMatchers.any());
    }

    private static PaperRetrievalSearchService.PaperRetrievalHit hitWithRrfScore(double rrfScore) {
        PaperVectorDocument document = new PaperVectorDocument();
        document.setPaperMd5("paper-md5");
        document.setChunkIndex(1);
        document.setTitle("Weakly related paper");
        document.setFileName("weak.pdf");
        document.setRawText("This chunk is not enough to answer the question.");
        return new PaperRetrievalSearchService.PaperRetrievalHit(
                document,
                0.7d,
                1,
                PaperRetrievalSearchService.RetrievalSource.VECTOR,
                1,
                null,
                rrfScore
        );
    }
}
