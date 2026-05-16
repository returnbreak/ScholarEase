package com.kesf.backend.utils;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kesf.backend.config.MinerUProperties;
import com.kesf.backend.exception.BusinessException;
import com.kesf.backend.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
/**
 * MinerU 服务的 HTTP 客户端封装。
 * 负责与底层的 MinerU REST API 进行网络交互，包括获取上传凭证、上传文件流以及拉取解析结果。
 */
public class MinerUClient {

    // 全局请求超时时间设置为 60 秒
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);
    // MinerU 接口定义的正常业务响应状态码为 0
    private static final int SUCCESS_CODE = 0;

    // 注入配置文件中读取的 MinerU 相关属性（如 BaseUrl, Token 等）
    private final MinerUProperties properties;
    // 注入 Jackson 的 ObjectMapper，用于 JSON 序列化与反序列化
    private final ObjectMapper objectMapper;

    // 复用单例的 Java 11 原生 HttpClient
    private final HttpClient httpClient = HttpClient.newHttpClient();

    /**
     * 向 MinerU 服务请求一个用于上传文件的预签名 URL (Signed URL) 及任务批次 ID (batchId)。
     */
    public SignedUpload requestSignedUploadUrl(String fileName, String traceId) {
        // 校验 Token 是否配置
        requireToken();

        // 构造请求体，包含文件名、文件标识(traceId)和配置的模型版本
        Map<String, Object> requestBody = Map.of(
                "files", List.of(Map.of(
                        "name", fileName,
                        "data_id", traceId
                )),
                "model_version", properties.getModelVersion()
        );

        // 构建向 MinerU 请求签名的 HTTP POST 请求
        HttpRequest request = HttpRequest.newBuilder(apiUri(properties.getApi().getFileUrlsBatchPath()))
                .timeout(REQUEST_TIMEOUT)
                .header("Authorization", "Bearer " + properties.getToken())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(writeJson(requestBody)))
                .build();

        // 发送请求，并断言返回成功，提取返回的 data 节点
        JsonNode data = requireSuccessfulMinerUData(send(request), "request signed upload URL");
        // 提取批次号
        String batchId = text(data, "batch_id");
        // 提取文件签名 URL 列表（因为本次只传了一个文件，所以取第一个即可）
        JsonNode fileUrls = data.path("file_urls");
        String signedUrl = fileUrls.isArray() && !fileUrls.isEmpty() ? fileUrls.get(0).asText(null) : null;
        
        if (!StringUtils.hasText(batchId) || !StringUtils.hasText(signedUrl)) {
            throw minerUFailed("MinerU signed upload response missing batch_id or file_urls");
        }

        return new SignedUpload(batchId, signedUrl);
    }

    /**
     * 将文件的原始字节数组（Byte Array）通过 HTTP PUT 推送到预签名 URL 中。
     */
    public void uploadToSignedUrl(String signedUrl, byte[] fileBytes) {
        // 创建 PUT 请求并将文件流写入 Body
        HttpRequest request = HttpRequest.newBuilder(URI.create(signedUrl))
                .timeout(REQUEST_TIMEOUT)
                .PUT(HttpRequest.BodyPublishers.ofByteArray(fileBytes))
                .build();

        HttpResponse<String> response = send(request);
        // 上传到对象存储通常返回 200 OK 视为成功
        if (response.statusCode() != 200) {
            throw minerUFailed("MinerU signed upload failed with HTTP " + response.statusCode());
        }
    }

    /**
     * 获取指定批次的 MinerU 文档解析结果。
     */
    public byte[] downloadFullZip(String fullZipUrl) {
        if (!StringUtils.hasText(fullZipUrl)) {
            throw minerUFailed("MinerU full ZIP URL is empty");
        }

        HttpRequest request = HttpRequest.newBuilder(URI.create(fullZipUrl))
                .timeout(REQUEST_TIMEOUT)
                .GET()
                .build();
        HttpResponse<byte[]> response = sendBytes(request);
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw minerUFailed("MinerU full ZIP download failed with HTTP " + response.statusCode());
        }
        return response.body();
    }

    public BatchFileResult getBatchResult(String batchId, String traceId) {
        requireToken();

        // 替换路径中的 {batchId} 占位符
        String path = properties.getApi().getBatchResultsPath().replace("{batchId}", batchId);
        HttpRequest request = HttpRequest.newBuilder(apiUri(path))
                .timeout(REQUEST_TIMEOUT)
                .header("Authorization", "Bearer " + properties.getToken())
                .GET()
                .build();

        JsonNode data = requireSuccessfulMinerUData(send(request), "get batch result");
        JsonNode results = data.path("extract_result");
        // 从结果数组中匹配当前文件对应的解析结果
        JsonNode result = findResult(results, traceId);
        if (result == null) {
            throw minerUFailed("MinerU batch result missing traceId " + traceId);
        }

        // 封装返回状态、错误信息和全量文件的压缩包下载地址
        return new BatchFileResult(
                text(result, "state"),
                text(result, "err_msg"),
                text(result, "full_zip_url")
        );
    }

    /**
     * 辅助方法：拼接基础 URL 和 API 路径，生成合法的 URI 对象。
     */
    private URI apiUri(String path) {
        String baseUrl = properties.getBaseUrl();
        String normalizedPath = path.startsWith("/") ? path : "/" + path;
        String normalizedBase = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        return URI.create(normalizedBase + normalizedPath);
    }

    /**
     * 辅助方法：在返回的解析结果数组中，依据 traceId (对应 data_id) 定位对应的文件。
     */
    private JsonNode findResult(JsonNode results, String traceId) {
        if (!results.isArray() || results.isEmpty()) {
            return null;
        }
        for (JsonNode result : results) {
            if (traceId.equals(text(result, "data_id"))) {
                return result;
            }
        }
        // 降级策略：如果没匹配上（通常不会发生），默认返回第一个结果
        return results.get(0);
    }

    /**
     * 辅助方法：校验 MinerU API 的 HTTP 响应码及业务状态码 (code)，如果成功则提取并返回 "data" 节点。
     */
    private JsonNode requireSuccessfulMinerUData(HttpResponse<String> response, String action) {
        // 检查 HTTP 状态码是否为 2xx 成功状态
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw minerUFailed("MinerU " + action + " failed with HTTP " + response.statusCode());
        }

        JsonNode root = readJson(response.body());
        // 提取业务 code
        int code = root.path("code").asInt(Integer.MIN_VALUE);
        if (code != SUCCESS_CODE) {
            // 如果不成功，尝试提取返回的 msg 并抛出包含具体原因的异常
            String message = text(root, "msg");
            throw minerUFailed(StringUtils.hasText(message) ? message : "MinerU " + action + " failed");
        }

        JsonNode data = root.path("data");
        if (data.isMissingNode() || data.isNull()) {
            throw minerUFailed("MinerU " + action + " response missing data");
        }
        return data;
    }

    /**
     * 辅助方法：发送 HTTP 请求并统一处理可能产生的网络或中断异常。
     */
    private HttpResponse<String> send(HttpRequest request) {
        try {
            return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw minerUFailed("MinerU request interrupted");
        } catch (IOException exception) {
            throw minerUFailed("MinerU request failed: " + exception.getMessage());
        }
    }

    private HttpResponse<byte[]> sendBytes(HttpRequest request) {
        try {
            return httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw minerUFailed("MinerU request interrupted");
        } catch (IOException exception) {
            throw minerUFailed("MinerU request failed: " + exception.getMessage());
        }
    }

    /**
     * 辅助方法：将对象序列化为 JSON 字符串。
     */
    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw minerUFailed("Failed to build MinerU request body");
        }
    }

    /**
     * 辅助方法：将字符串反序列化为 JsonNode 树对象。
     */
    private JsonNode readJson(String responseBody) {
        try {
            return objectMapper.readTree(responseBody);
        } catch (JsonProcessingException exception) {
            throw minerUFailed("MinerU response is not valid JSON");
        }
    }

    /**
     * 辅助方法：安全地提取 JsonNode 中的字符串字段值。
     */
    private String text(JsonNode node, String fieldName) {
        return node.path(fieldName).asText(null);
    }

    /**
     * 辅助方法：确保客户端正确配置了调用必需的 Token。
     */
    private void requireToken() {
        if (!StringUtils.hasText(properties.getToken())) {
            throw minerUFailed("MinerU token is not configured");
        }
    }

    /**
     * 辅助方法：封装生成特定于 MinerU 解析失败的自定义异常。
     */
    private BusinessException minerUFailed(String message) {
        return new BusinessException(ErrorCode.MINERU_PARSE_FAILED, message);
    }

    /**
     * 数据承载记录 (Record)：存储分配到的批次号和用于直传的签名 URL。
     */
    public record SignedUpload(String batchId, String signedUrl) {
    }

    /**
     * 数据承载记录 (Record)：存储一次解析的状态及可能产生的结果（错误消息或下载地址）。
     */
    public record BatchFileResult(String state, String errorMessage, String fullZipUrl) {
    }
}
