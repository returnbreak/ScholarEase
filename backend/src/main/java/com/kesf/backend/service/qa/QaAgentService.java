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
import java.util.concurrent.atomic.AtomicBoolean;
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
                    .filter(this::isReliableEvidence)
                    .limit(finalLimit)
                    .toList();
            List<QaCitationDTO> citations = citationAssembler.toCitations(finalHits);

            if (citations.size() < minimumEvidenceChunks()) {
                sendNoEvidenceResponse(request, sender, sessionId, messageId, qaTraceId);
                return;
            }

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
            messages.addAll(validMessages(memory.messages()));
            messages.add(UserMessage.from(groundedUserMessage));

            StringBuilder answerBuffer = new StringBuilder();
            AtomicBoolean streamFinished = new AtomicBoolean(false);
            streamingChatModel.chat(messages, new StreamingChatResponseHandler() {
                @Override
                public void onPartialResponse(String token) {
                    answerBuffer.append(token);
                    sender.accept(QaWebSocketResponse.token(token));
                }

                @Override
                public void onCompleteResponse(ChatResponse response) {
                    if (!streamFinished.compareAndSet(false, true)) {
                        return;
                    }
                    memory.add(UserMessage.from(request.getMessage()));
                    AiMessage aiMessage = validAiMessage(response.aiMessage(), answerBuffer.toString());
                    if (aiMessage != null) {
                        memory.add(aiMessage);
                    }
                    qaSessionService.recordExchange(
                            sessionId,
                            request.getSessionTitle(),
                            request.getMessage(),
                            aiMessage == null ? answerBuffer.toString() : aiMessage.text(),
                            citations
                    );
                    sender.accept(QaWebSocketResponse.completion(metadata));
                }

                @Override
                public void onError(Throwable error) {
                    if (!streamFinished.compareAndSet(false, true)) {
                        return;
                    }
                    if (isUpstreamStreamClosed(error) && StringUtils.hasText(answerBuffer)) {
                        log.warn("QA upstream stream closed after partial response, qaTraceId={}", qaTraceId, error);
                        completeInterruptedStream(request, sender, sessionId, metadata, citations, memory, answerBuffer);
                        return;
                    }
                    log.error("QA stream failed, qaTraceId={}", qaTraceId, error);
                    sender.accept(QaWebSocketResponse.error(safeErrorMessage(error)));
                }
            });
        } catch (Exception exception) {
            log.error("QA stream preparation failed, qaTraceId={}", qaTraceId, exception);
            sender.accept(QaWebSocketResponse.error(safeErrorMessage(exception)));
        }
    }

    private void completeInterruptedStream(QaChatStreamRequest request,
                                           Consumer<QaWebSocketResponse> sender,
                                           String sessionId,
                                           QaStreamMetadataDTO metadata,
                                           List<QaCitationDTO> citations,
                                           ChatMemory memory,
                                           StringBuilder answerBuffer) {
        String interruptionNotice = "\n\n回答生成中断，请稍后重试。";
        answerBuffer.append(interruptionNotice);
        sender.accept(QaWebSocketResponse.token(interruptionNotice));
        String finalAnswer = answerBuffer.toString();
        memory.add(UserMessage.from(request.getMessage()));
        memory.add(AiMessage.from(finalAnswer));
        qaSessionService.recordExchange(
                sessionId,
                request.getSessionTitle(),
                request.getMessage(),
                finalAnswer,
                citations
        );
        sender.accept(QaWebSocketResponse.completion(metadata));
    }

    private boolean isUpstreamStreamClosed(Throwable error) {
        Throwable current = error;
        while (current != null) {
            String message = current.getMessage();
            if (message != null && "closed".equalsIgnoreCase(message.trim())) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private String safeErrorMessage(Throwable error) {
        if (isUpstreamStreamClosed(error)) {
            return "模型流式连接提前关闭，请重试。";
        }
        return StringUtils.hasText(error.getMessage()) ? error.getMessage() : "问答生成失败，请重试。";
    }

    private void sendNoEvidenceResponse(QaChatStreamRequest request,
                                        Consumer<QaWebSocketResponse> sender,
                                        String sessionId,
                                        String messageId,
                                        String qaTraceId) {
        String noEvidenceMessage = qaProperties.getPrompts().getNoEvidenceMessage().strip();
        sender.accept(QaWebSocketResponse.token(noEvidenceMessage));
        QaStreamMetadataDTO metadata = QaStreamMetadataDTO.builder()
                .sessionId(sessionId)
                .messageId(messageId)
                .qaTraceId(qaTraceId)
                .citations(List.of())
                .build();
        sender.accept(QaWebSocketResponse.completion(metadata));
        ChatMemory memory = chatMemoryProvider.get(sessionId);
        memory.add(UserMessage.from(request.getMessage()));
        memory.add(AiMessage.from(noEvidenceMessage));
        qaSessionService.recordExchange(
                sessionId,
                request.getSessionTitle(),
                request.getMessage(),
                noEvidenceMessage,
                List.of()
        );
    }

    private boolean isReliableEvidence(PaperRetrievalSearchService.PaperRetrievalHit hit) {
        return evidenceScore(hit) >= minimumEvidenceScore();
    }

    private double evidenceScore(PaperRetrievalSearchService.PaperRetrievalHit hit) {
        if (hit == null) {
            return 0.0d;
        }
        if (hit.rrfScore() != null && hit.rrfScore() > 0) {
            return hit.rrfScore();
        }
        return hit.score() == null ? 0.0d : hit.score();
    }

    private double minimumEvidenceScore() {
        return Math.max(0.0d, qaProperties.getMinimumEvidenceScore());
    }

    private int minimumEvidenceChunks() {
        return Math.max(1, qaProperties.getMinimumEvidenceChunks());
    }

    private List<ChatMessage> validMessages(List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }
        return messages.stream()
                .filter(this::isValidMessage)
                .toList();
    }

    private boolean isValidMessage(ChatMessage message) {
        if (message instanceof AiMessage aiMessage) {
            return StringUtils.hasText(aiMessage.text()) || aiMessage.hasToolExecutionRequests();
        }
        return message != null;
    }

    private AiMessage validAiMessage(AiMessage responseMessage, String fallbackText) {
        if (responseMessage != null
                && (StringUtils.hasText(responseMessage.text()) || responseMessage.hasToolExecutionRequests())) {
            return responseMessage;
        }
        if (StringUtils.hasText(fallbackText)) {
            return AiMessage.from(fallbackText);
        }
        return null;
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
