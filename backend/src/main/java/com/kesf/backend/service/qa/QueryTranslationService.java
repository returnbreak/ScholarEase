package com.kesf.backend.service.qa;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kesf.backend.config.QaProperties;
import com.kesf.backend.service.PaperRetrievalSearchService;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class QueryTranslationService {

    private static final Pattern ASCII_TERM = Pattern.compile("[A-Za-z][A-Za-z0-9_+\\-./]{1,}");
    private static final int REWRITE_TIMEOUT_SECONDS = 60;

    private final ObjectMapper objectMapper;
    private final QaProperties qaProperties;

    public QueryRewriteResult rewrite(String userMessage, StreamingChatModel chatModel) {
        try {
            return rewriteByStreamingModel(userMessage, chatModel);
        } catch (Exception exception) {
            log.warn("Query rewrite model call failed, fallback to local rewrite: {}", exception.getMessage());
            return fallbackRewrite(userMessage);
        }
    }

    private QueryRewriteResult rewriteByStreamingModel(String userMessage, StreamingChatModel chatModel) throws Exception {
        if (!StringUtils.hasText(userMessage)) {
            return fallbackRewrite(userMessage);
        }

        StringBuilder buffer = new StringBuilder();
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        chatModel.chat(rewritePrompt(userMessage), new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(String token) {
                buffer.append(token);
            }

            @Override
            public void onCompleteResponse(ChatResponse response) {
                latch.countDown();
            }

            @Override
            public void onError(Throwable error) {
                errorRef.set(error);
                latch.countDown();
            }
        });

        if (!latch.await(REWRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Query rewrite timed out");
        }
        if (errorRef.get() != null) {
            throw new IllegalStateException(errorRef.get().getMessage(), errorRef.get());
        }
        return parseRewriteJson(buffer.toString());
    }

    private QueryRewriteResult parseRewriteJson(String raw) throws Exception {
        JsonNode node = objectMapper.readTree(stripJsonFence(raw));
        String standaloneQuestionZh = text(node, "standaloneQuestionZh");
        String queryEn = text(node, "queryEn");
        List<String> bm25Keywords = stringList(node.get("bm25Keywords"));
        List<String> exactTerms = stringList(node.get("exactTerms"));

        if (!StringUtils.hasText(standaloneQuestionZh)) {
            standaloneQuestionZh = text(node, "queryZh");
        }
        if (!StringUtils.hasText(queryEn)) {
            queryEn = standaloneQuestionZh;
        }
        return new QueryRewriteResult(standaloneQuestionZh, queryEn, bm25Keywords, exactTerms);
    }

    private String rewritePrompt(String userMessage) {
        return qaProperties.getPrompts().getRewritePrompt()
                .replace("{message}", userMessage == null ? "" : userMessage);
    }

    private QueryRewriteResult fallbackRewrite(String userMessage) {
        String standaloneQuestionZh = StringUtils.hasText(userMessage) ? userMessage.trim() : "";
        List<String> exactTerms = extractAsciiTerms(standaloneQuestionZh);
        String queryEn = buildFallbackEnglishQuery(standaloneQuestionZh, exactTerms);
        return new QueryRewriteResult(standaloneQuestionZh, queryEn, exactTerms, exactTerms);
    }

    private String stripJsonFence(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.startsWith("```")) {
            value = value.replaceFirst("^```(?:json)?", "").trim();
        }
        if (value.endsWith("```")) {
            value = value.substring(0, value.length() - 3).trim();
        }
        return value;
    }

    private String text(JsonNode node, String fieldName) {
        JsonNode value = node.get(fieldName);
        return value == null || value.isNull() ? "" : value.asText("");
    }

    private List<String> stringList(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (JsonNode item : node) {
            String value = item.asText("");
            if (StringUtils.hasText(value)) {
                values.add(value);
            }
        }
        return values;
    }

    private String buildFallbackEnglishQuery(String question, List<String> exactTerms) {
        if (exactTerms.isEmpty()) {
            return question;
        }
        Set<String> parts = new LinkedHashSet<>(exactTerms);
        parts.add(question);
        return String.join(" ", parts);
    }

    private List<String> extractAsciiTerms(String question) {
        if (!StringUtils.hasText(question)) {
            return List.of();
        }
        Matcher matcher = ASCII_TERM.matcher(question);
        List<String> terms = new ArrayList<>();
        while (matcher.find()) {
            String term = matcher.group();
            if (term.length() >= 2) {
                terms.add(term);
            }
        }
        return terms;
    }

    public record QueryRewriteResult(
            String standaloneQuestionZh,
            String queryEn,
            List<String> bm25Keywords,
            List<String> exactTerms
    ) {
        public PaperRetrievalSearchService.RetrievalQuery toRetrievalQuery() {
            return new PaperRetrievalSearchService.RetrievalQuery(
                    standaloneQuestionZh,
                    queryEn,
                    bm25Keywords,
                    exactTerms
            );
        }
    }
}
