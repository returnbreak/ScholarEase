package com.kesf.backend.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kesf.backend.config.EmbeddingProperties;
import com.kesf.backend.service.EmbeddingClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * OpenAI-compatible Embedding 客户端。
 * <p>
 * 默认对接 DeepInfra 兼容 OpenAI 格式的 Embedding API（/v1/openai/embeddings）。
 * 支持批量文本输入，自动按 {@code batchSize} 分批调用，返回与输入文本等长的向量列表。
 * </p>
 * <p>
 * API 请求格式：
 * </p>
 * <pre>
 * POST /v1/openai/embeddings
 * {
 *   "model": "BAAI/bge-m3",
 *   "input": ["text1", "text2", ...],
 *   "encoding_format": "float"
 * }
 * </pre>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OpenAiCompatibleEmbeddingClient implements EmbeddingClient {

    private final EmbeddingProperties properties;
    private final ObjectMapper objectMapper;

    /** JDK 内置 HttpClient，复用连接池，避免每次请求重新建连 */
    private final HttpClient httpClient = HttpClient.newHttpClient();

    /**
     * 将文本列表向量化。
     * <p>
     * 若 texts 为空或 null，直接返回空列表。若 API Key 未配置，抛出异常。
     * 超出 batchSize 的文本会自动分批调用，然后将各批次的结果拼接返回。
     * </p>
     *
     * @param texts 待向量化的文本列表
     * @return 与输入等长的 float[] 列表，顺序一一对应
     */
    @Override
    public List<float[]> embed(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }
        if (!StringUtils.hasText(properties.getKey())) {
            throw new IllegalStateException("Embedding API key is empty");
        }

        int batchSize = Math.max(1, properties.getBatchSize());
        int maxConcurrency = Math.max(1, properties.getMaxConcurrency());
        if (maxConcurrency == 1 || texts.size() <= batchSize) {
            return embedSequentially(texts, batchSize);
        }

        List<List<String>> batches = batches(texts, batchSize);
        ExecutorService executorService = Executors.newFixedThreadPool(
                Math.min(maxConcurrency, batches.size()),
                embeddingThreadFactory()
        );
        try {
            List<CompletableFuture<List<float[]>>> futures = batches.stream()
                    .map(batch -> CompletableFuture.supplyAsync(
                            () -> parseVectors(callApiOnce(batch)),
                            executorService
                    ))
                    .toList();
            List<float[]> vectors = new ArrayList<>(texts.size());
            for (CompletableFuture<List<float[]>> future : futures) {
                vectors.addAll(joinBatch(future));
            }
            return vectors;
        } finally {
            executorService.shutdownNow();
        }
    }

    private List<float[]> embedSequentially(List<String> texts, int batchSize) {
        List<float[]> vectors = new ArrayList<>(texts.size());
        for (int start = 0; start < texts.size(); start += batchSize) {
            int end = Math.min(start + batchSize, texts.size());
            List<String> batch = texts.subList(start, end);
            vectors.addAll(parseVectors(callApiOnce(batch)));
        }
        return vectors;
    }

    private List<List<String>> batches(List<String> texts, int batchSize) {
        List<List<String>> batches = new ArrayList<>();
        for (int start = 0; start < texts.size(); start += batchSize) {
            int end = Math.min(start + batchSize, texts.size());
            batches.add(texts.subList(start, end));
        }
        return batches;
    }

    private List<float[]> joinBatch(CompletableFuture<List<float[]>> future) {
        try {
            return future.join();
        } catch (CompletionException exception) {
            Throwable cause = exception.getCause() == null ? exception : exception.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IllegalStateException("Embedding API batch failed: " + cause.getMessage(), cause);
        }
    }

    private ThreadFactory embeddingThreadFactory() {
        AtomicInteger index = new AtomicInteger(1);
        return runnable -> {
            Thread thread = new Thread(runnable, "embedding-batch-" + index.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        };
    }

    /**
     * 单次 API 调用。
     *
     * @param batch 当前批次的文本列表（不超过 batchSize）
     * @return API 返回的 JSON 响应体字符串
     */
    private String callApiOnce(List<String> batch) {
        try {
            Map<String, Object> requestBody = new LinkedHashMap<>();
            requestBody.put("model", properties.getModel());
            requestBody.put("input", batch);
            requestBody.put("encoding_format", "float"); // 返回 float 数组而非 base64 编码

            HttpRequest request = HttpRequest.newBuilder(embeddingEndpoint())
                    .timeout(Duration.ofSeconds(Math.max(1, properties.getTimeoutSeconds())))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + properties.getKey())
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(requestBody)))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("Embedding API failed: HTTP "
                        + response.statusCode() + " - " + response.body());
            }
            return response.body();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt(); // 恢复中断状态
            throw new IllegalStateException("Embedding API call interrupted", exception);
        } catch (Exception exception) {
            throw new IllegalStateException("Embedding API call failed: " + exception.getMessage(), exception);
        }
    }

    /** 拼接完整的 Embedding API 端点 URL */
    private URI embeddingEndpoint() {
        String baseUrl = properties.getUrl();
        // 去除末尾斜杠，避免双斜杠
        String normalized = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        return URI.create(normalized + "/embeddings");
    }

    /**
     * 解析 API 返回的 JSON 响应，提取向量数组。
     * <p>
     * 响应格式为 {@code {"data": [{"embedding": [f1, f2, ...]}, ...]}}。
     * </p>
     *
     * @param response API 响应的 JSON 字符串
     * @return 向量列表，与请求中的输入文本顺序一致
     */
    private List<float[]> parseVectors(String response) {
        try {
            JsonNode data = objectMapper.readTree(response).get("data");
            if (data == null || !data.isArray()) {
                throw new IllegalStateException("Embedding API response missing data array");
            }
            List<float[]> vectors = new ArrayList<>(data.size());
            for (JsonNode item : data) {
                JsonNode embedding = item.get("embedding");
                if (embedding == null || !embedding.isArray()) {
                    throw new IllegalStateException("Embedding API response item missing embedding array");
                }
                float[] vector = new float[embedding.size()];
                for (int i = 0; i < embedding.size(); i++) {
                    vector[i] = (float) embedding.get(i).asDouble();
                }
                vectors.add(vector);
            }
            return vectors;
        } catch (Exception exception) {
            throw new IllegalStateException("Embedding API response parse failed: " + exception.getMessage(), exception);
        }
    }
}
