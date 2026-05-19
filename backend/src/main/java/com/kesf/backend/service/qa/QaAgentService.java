package com.kesf.backend.service.qa;

import com.kesf.backend.config.QaProperties;
import com.kesf.backend.dto.qa.QaChatStreamRequest;
import com.kesf.backend.dto.qa.QaCitationDTO;
import com.kesf.backend.dto.qa.QaStreamMetadataDTO;
import com.kesf.backend.dto.qa.QaWebSocketResponse;
import com.kesf.backend.service.PaperRetrievalSearchService;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

@Slf4j
@Service
@RequiredArgsConstructor
public class QaAgentService {

    private final ChatMemoryProvider chatMemoryProvider;
    private final QueryTranslationService queryTranslationService;
    private final PaperRetrievalSearchService retrievalSearchService;
    private final CitationAssembler citationAssembler;
    private final QaSessionService qaSessionService;
    private final QaStreamingChatModelFactory chatModelFactory;
    private final QaProperties qaProperties;

    public void streamAnswer(QaChatStreamRequest request, Consumer<QaWebSocketResponse> sender) {
        String sessionId = ensureSessionId(request.getSessionId());
        String messageId = UUID.randomUUID().toString();
        String qaTraceId = UUID.randomUUID().toString().replace("-", "");

        try {
            StreamingChatModel streamingChatModel = chatModelFactory.create(request.getModelConfig());
            QueryTranslationService.QueryRewriteResult rewrite =
                    queryTranslationService.rewrite(request.getMessage(), streamingChatModel);
            List<PaperRetrievalSearchService.PaperRetrievalHit> hits =
                    retrievalSearchService.searchHybrid(rewrite.toRetrievalQuery());
            int finalLimit = Math.max(1, qaProperties.getFinalContextMaxChunks());
            List<PaperRetrievalSearchService.PaperRetrievalHit> finalHits = hits.stream()
                    .limit(finalLimit)
                    .toList();
            List<QaCitationDTO> citations = citationAssembler.toCitations(finalHits);
            String evidence = citationAssembler.toEvidencePrompt(citations);

            QaStreamMetadataDTO metadata = QaStreamMetadataDTO.builder()
                    .sessionId(sessionId)
                    .messageId(messageId)
                    .qaTraceId(qaTraceId)
                    .standaloneQuestionZh(rewrite.standaloneQuestionZh())
                    .queryEn(rewrite.queryEn())
                    .citations(citations)
                    .build();
            sender.accept(QaWebSocketResponse.metadata(metadata));

            ChatMemory memory = chatMemoryProvider.get(sessionId);
            String groundedUserMessage = buildGroundedUserMessage(request.getMessage(), evidence);
            
            List<ChatMessage> messages = new ArrayList<>();
            messages.add(SystemMessage.from(systemPrompt()));
            messages.addAll(memory.messages());
            messages.add(UserMessage.from(groundedUserMessage));

            StringBuilder answerBuffer = new StringBuilder();
            streamingChatModel.chat(messages, new StreamingChatResponseHandler() {
                @Override
                public void onPartialResponse(String token) {
                    answerBuffer.append(token);
                    sender.accept(QaWebSocketResponse.token(token));
                }

                @Override
                public void onCompleteResponse(ChatResponse response) {
                    memory.add(UserMessage.from(request.getMessage()));
                    AiMessage aiMessage = response.aiMessage() == null
                            ? AiMessage.from(answerBuffer.toString())
                            : response.aiMessage();
                    memory.add(aiMessage);
                    qaSessionService.recordExchange(
                            sessionId,
                            request.getSessionTitle(),
                            request.getMessage(),
                            aiMessage.text(),
                            citations
                    );
                    sender.accept(QaWebSocketResponse.completion(metadata));
                }

                @Override
                public void onError(Throwable error) {
                    log.error("QA stream failed, qaTraceId={}", qaTraceId, error);
                    sender.accept(QaWebSocketResponse.error(error.getMessage()));
                }
            });
        } catch (Exception exception) {
            log.error("QA stream preparation failed, qaTraceId={}", qaTraceId, exception);
            sender.accept(QaWebSocketResponse.error(exception.getMessage()));
        }
    }

    private String buildGroundedUserMessage(String userMessage, String evidence) {
        return qaProperties.getPrompts().getGroundedUserMessage()
                .replace("{message}", userMessage == null ? "" : userMessage)
                .replace("{evidence}", evidence == null ? "" : evidence);
    }

    private String systemPrompt() {
        return qaProperties.getPrompts().getSystemPrompt();
    }

    private String ensureSessionId(String sessionId) {
        if (!StringUtils.hasText(sessionId)) {
            throw new IllegalArgumentException("sessionId is empty, please create QA session first");
        }
        return sessionId;
    }
}
