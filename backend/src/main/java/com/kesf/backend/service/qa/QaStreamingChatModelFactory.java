package com.kesf.backend.service.qa;

import com.kesf.backend.config.QaProperties;
import com.kesf.backend.dto.qa.QaModelConfigDTO;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;

@Service
@RequiredArgsConstructor
@Slf4j
public class QaStreamingChatModelFactory {

    private final QaProperties qaProperties;

    public StreamingChatModel create(QaModelConfigDTO requestConfig) {
        QaProperties.Chat defaults = qaProperties.getChat();
        String baseUrl = textOrDefault(requestConfig == null ? null : requestConfig.getBaseUrl(), defaults.getBaseUrl());
        String apiKey = textOrDefault(requestConfig == null ? null : requestConfig.getApiKey(), defaults.getApiKey());
        String modelName = textOrDefault(requestConfig == null ? null : requestConfig.getModelName(), defaults.getModelName());
        Double temperature = doubleOrDefault(requestConfig == null ? null : requestConfig.getTemperature(), defaults.getTemperature());
        Double topP = doubleOrDefault(requestConfig == null ? null : requestConfig.getTopP(), defaults.getTopP());
        Integer maxTokens = resolveMaxTokens(requestConfig);
        Integer timeoutSeconds = resolveTimeoutSeconds(requestConfig);

        if (!StringUtils.hasText(apiKey)) {
            throw new IllegalArgumentException("LLM apiKey is empty, please configure DEEPSEEK_API_KEY or input apiKey in model settings");
        }

        log.info("Create QA streaming model: baseUrl={}, modelName={}, maxTokens={}, timeoutSeconds={}",
                baseUrl, modelName, maxTokens, timeoutSeconds);

        return OpenAiStreamingChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .modelName(modelName)
                .temperature(temperature)
                .topP(topP)
                .maxTokens(maxTokens)
                .timeout(Duration.ofSeconds(Math.max(1, timeoutSeconds)))
                .build();
    }

    private String textOrDefault(String value, String defaultValue) {
        return StringUtils.hasText(value) ? value.trim() : defaultValue;
    }

    private Double doubleOrDefault(Double value, Double defaultValue) {
        return value == null ? defaultValue : value;
    }

    int resolveMaxTokens(QaModelConfigDTO requestConfig) {
        int defaultMaxTokens = Math.max(1, qaProperties.getChat().resolveMaxTokens());
        Integer requestedMaxTokens = requestConfig == null ? null : requestConfig.getMaxTokens();
        if (requestedMaxTokens == null) {
            return defaultMaxTokens;
        }
        return Math.max(defaultMaxTokens, requestedMaxTokens);
    }

    int resolveTimeoutSeconds(QaModelConfigDTO requestConfig) {
        int defaultTimeoutSeconds = Math.max(1, qaProperties.getChat().getTimeoutSeconds());
        Integer requestedTimeoutSeconds = requestConfig == null ? null : requestConfig.getTimeoutSeconds();
        if (requestedTimeoutSeconds == null) {
            return defaultTimeoutSeconds;
        }
        return Math.max(defaultTimeoutSeconds, requestedTimeoutSeconds);
    }
}
