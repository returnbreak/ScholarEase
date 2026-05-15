package com.kesf.backend.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kesf.backend.config.ZoteroProperties;
import com.kesf.backend.exception.BusinessException;
import com.kesf.backend.exception.ErrorCode;
import com.kesf.backend.service.ZoteroImportService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Zotero 导入服务实现类
 * 负责将 PDF 文档发送到本地或远程的 Zotero 服务进行保存与解析
 */
@Service
@RequiredArgsConstructor
public class ZoteroImportServiceImpl implements ZoteroImportService {

    // Zotero 保存独立附件的 API 接口路径
    private static final String SAVE_STANDALONE_ATTACHMENT_PATH = "/connector/saveStandaloneAttachment";

    // Zotero 相关的配置属性（如服务地址、超时时间等）
    private final ZoteroProperties properties;
    // 用于 JSON 的序列化和反序列化
    private final ObjectMapper objectMapper;
    // JDK 11+ 内置的 HTTP 客户端
    private final HttpClient httpClient = HttpClient.newHttpClient();

    /**
     * 将解析后的论文（PDF）导入到 Zotero 客户端
     *
     * @param pdfBytes PDF 文件的字节数据
     * @param fileName 原始文件名
     * @param traceId  业务追踪 ID
     * @return ZoteroImportResult 包含 Zotero 会话 ID 和是否能识别该文档的标识
     */
    @Override
    public ZoteroImportResult importParsedPaper(byte[] pdfBytes, String fileName, String traceId) {
        // 检查 Zotero 导入功能是否在配置中开启
        if (!properties.isEnabled()) {
            throw zoteroFailed("Zotero import is disabled");
        }
        // 校验传入的 PDF 数据
        if (pdfBytes == null || pdfBytes.length == 0) {
            throw zoteroFailed("Zotero import requires PDF bytes");
        }

        // 获取处理过的安全文件名
        String safeFileName = safeFileName(fileName);
        String sourceUrl = "scholarease://documents/" + safeTraceId(traceId);
        // 基于 traceId 生成唯一的会话 ID
        String sessionId = sessionId(traceId);
        
        // 构建传递给 Zotero 的元数据 (将会放在 X-Metadata 请求头中)
        Map<String, String> metadata = Map.of(
                "sessionID", sessionId, // 必填：会话 ID
                "title", titleFromFileName(safeFileName), // 默认标题：从文件名截取
                "url", sourceUrl // 来源链接，方便在 Zotero 中溯源
        );

        // 组装 HTTP POST 请求
        HttpRequest request = HttpRequest.newBuilder(apiUri(SAVE_STANDALONE_ATTACHMENT_PATH))
                .timeout(properties.getRequestTimeout()) // 设置超时时间
                .header("Content-Type", "application/pdf") // 声明内容类型为 PDF
                .header("X-Metadata", writeJson(metadata)) // 将元数据转为 JSON 字符串
                .POST(HttpRequest.BodyPublishers.ofByteArray(pdfBytes)) // 放入 PDF 字节数据
                .build();

        // 执行请求并获取响应
        HttpResponse<String> response = send(request);
        
        // Zotero 创建附件成功会返回 201 Created 状态码
        if (response.statusCode() != 201) {
            throw zoteroFailed("Zotero import failed with HTTP " + response.statusCode());
        }

        // 解析返回体以判断 Zotero 是否成功识别了该论文，并封装为结果返回
        boolean canRecognize = readCanRecognize(response.body());
        return new ZoteroImportResult(sessionId, canRecognize);
    }

    /**
     * 构建 Zotero API 的完整请求 URI
     * 处理 baseUrl 和 path 之间可能存在的斜杠问题
     *
     * @param path API 路径
     * @return 完整的 URI 对象
     */
    private URI apiUri(String path) {
        String normalizedBase = properties.getBaseUrl().endsWith("/")
                ? properties.getBaseUrl().substring(0, properties.getBaseUrl().length() - 1)
                : properties.getBaseUrl();
        String normalizedPath = path.startsWith("/") ? path : "/" + path;
        return URI.create(normalizedBase + normalizedPath);
    }

    /**
     * 发送 HTTP 请求并统一处理异常
     *
     * @param request 构建好的 HttpRequest
     * @return HttpResponse
     */
    private HttpResponse<String> send(HttpRequest request) {
        try {
            return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException exception) {
            // 恢复线程的中断状态
            Thread.currentThread().interrupt();
            throw zoteroFailed("Zotero import interrupted");
        } catch (IOException exception) {
            throw zoteroFailed("Zotero import request failed: " + exception.getMessage());
        }
    }

    /**
     * 将 Java 对象序列化为 JSON 字符串
     *
     * @param value 要序列化的对象 (通常是 Map)
     * @return JSON 字符串
     */
    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw zoteroFailed("Failed to build Zotero metadata");
        }
    }

    /**
     * 读取 Zotero 的响应 JSON 并解析 canRecognize 字段
     *
     * @param responseBody Zotero 返回的 JSON 字符串
     * @return 布尔值，表示 Zotero 是否能提取出论文元数据
     */
    private boolean readCanRecognize(String responseBody) {
        if (!StringUtils.hasText(responseBody)) {
            return false;
        }
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            return root.path("canRecognize").asBoolean(false);
        } catch (JsonProcessingException exception) {
            return false;
        }
    }

    /**
     * 从路径中提取出安全的文件名
     *
     * @param fileName 原始文件名
     * @return 纯文件名，如果为空则返回默认名 "paper.pdf"
     */
    private String safeFileName(String fileName) {
        String safeName = StringUtils.getFilename(fileName);
        return StringUtils.hasText(safeName) ? safeName : "paper.pdf";
    }

    /**
     * 尝试从文件名中截取出标题 (去掉 .pdf 后缀)
     *
     * @param fileName 文件名
     * @return 论文标题
     */
    private String titleFromFileName(String fileName) {
        if (!StringUtils.hasText(fileName)) {
            return "Uploaded PDF";
        }
        // 如果以 .pdf 结尾 (忽略大小写)，截取掉最后 4 个字符
        return fileName.toLowerCase(Locale.ROOT).endsWith(".pdf")
                ? fileName.substring(0, fileName.length() - 4)
                : fileName;
    }

    /**
     * 根据 traceId 生成包含 UUID 的 Zotero Session ID
     *
     * @param traceId 追踪 ID
     * @return 生成的 Session ID 字符串
     */
    private String sessionId(String traceId) {
        return "scholarease-" + safeTraceId(traceId) + "-" + UUID.randomUUID().toString().replace("-", "");
    }

    /**
     * 确保 traceId 中仅包含安全字符 (字母、数字、下划线、短横线)
     *
     * @param traceId 原始追踪 ID
     * @return 过滤后的追踪 ID
     */
    private String safeTraceId(String traceId) {
        if (!StringUtils.hasText(traceId)) {
            return "unknown";
        }
        // 将所有非 [A-Za-z0-9_-] 的字符替换为短横线
        return traceId.replaceAll("[^A-Za-z0-9_-]", "-");
    }

    /**
     * 构建 Zotero 导入失败的业务异常
     *
     * @param message 错误详情
     * @return BusinessException
     */
    private BusinessException zoteroFailed(String message) {
        return new BusinessException(ErrorCode.ZOTERO_WRITE_FAILED, message);
    }
}
