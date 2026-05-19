package com.kesf.backend.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonGenerator;
import com.kesf.backend.config.ZoteroProperties;
import com.kesf.backend.exception.BusinessException;
import com.kesf.backend.exception.ErrorCode;
import com.kesf.backend.service.ZoteroImportService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Zotero 导入服务实现类
 * 负责将 PDF 文档发送到本地或远程的 Zotero 服务进行保存与解析
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ZoteroImportServiceImpl implements ZoteroImportService {

    // Zotero 保存独立附件的 API 接口路径
    private static final String SAVE_STANDALONE_ATTACHMENT_PATH = "/connector/saveStandaloneAttachment";
    private static final String LOCAL_ITEMS_PATH = "/api/users/0/items";
    private static final Pattern YEAR_PATTERN = Pattern.compile("(\\d{4})");

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
        long previousLibraryVersion = currentLibraryVersion();
        
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
                .header("X-Metadata", writeHeaderJson(metadata)) // Header 只能安全携带 ASCII，中文会被转义为 JSON unicode
                .POST(HttpRequest.BodyPublishers.ofByteArray(pdfBytes)) // 放入 PDF 字节数据
                .build();

        // 执行请求并获取响应
        HttpResponse<String> response = send(request);
        
        // Zotero 创建附件成功会返回 201 Created 状态码
        if (response.statusCode() != 201) {
            throw zoteroFailed("Zotero import failed with HTTP " + response.statusCode());
        }

        // 解析返回体以判断 Zotero 是否成功识别了该论文。识别失败不阻断后续 MinerU 流程，
        // 后面会尝试读取附件记录，仍不可用时回退到文件名元数据。
        boolean canRecognize = readCanRecognize(response.body());
        if (!canRecognize) {
            log.warn("Zotero connector reported canRecognize=false, fallback metadata will be used: sourceUrl={}", sourceUrl);
        }
        RecognizedResult recognized = waitForRecognizedMetadata(sourceUrl, previousLibraryVersion, safeFileName);
        String collectionName = lookupFirstCollectionName(recognized.itemKey());
        return new ZoteroImportResult(sessionId, recognized.canRecognize(), recognized.metadata(),
                recognized.itemKey(), collectionName);
    }

    /**
     * 将指定的条目移至 Zotero 回收站
     *
     * @param itemKey 要删除的 Zotero 条目唯一 Key
     */
    @Override
    public void deleteItem(String itemKey) {
        // 检查 Zotero 功能是否在配置中开启，未开启则抛出异常
        if (!properties.isEnabled()) {
            throw zoteroFailed("Zotero import is disabled");
        }
        // 如果传入的 itemKey 为空或仅包含空白字符，直接返回，无需发起无意义请求
        if (!StringUtils.hasText(itemKey)) {
            return;
        }
        
        // 获取安全的 itemKey，过滤掉可能导致异常或注入的非法字符
        String safeKey = safeItemKey(itemKey);
        
        // 构造要发送到 Zotero 本地服务端的 JSON 请求体
        // "operation": "trash_item" 指明了当前操作为将条目移入回收站
        Map<String, Object> requestBody = Map.of("operation", "trash_item", "item_key", safeKey);
        
        // 组装 HTTP POST 请求，指向提供写操作支持的 /write 接口
        HttpRequest request = HttpRequest.newBuilder(apiUri("/write"))
                .timeout(properties.getRequestTimeout()) // 设置请求超时时间
                .header("Content-Type", "application/json") // 声明发送的数据格式为 JSON
                .POST(HttpRequest.BodyPublishers.ofString(writeJson(requestBody))) // 将请求体转换为 JSON 字符串并放入请求
                .build();
                
        // 发送 HTTP 请求并获取响应
        HttpResponse<String> response = send(request);
        
        // 检查 HTTP 响应状态码，如果不为 200 (OK)，则抛出删除失败的异常
        if (response.statusCode() != 200) {
            throw zoteroFailed("Zotero trash item " + safeKey + " failed with HTTP " + response.statusCode());
        }
        
        JsonNode result;
        try {
            // 尝试将响应体解析为 JSON 树节点
            result = objectMapper.readTree(response.body());
        } catch (JsonProcessingException e) {
            // 如果解析 JSON 失败（格式不正确），则抛出异常
            throw zoteroFailed("Zotero write response is not valid JSON");
        }
        
        // 检查业务处理结果：判断 JSON 中的 "success" 字段是否为 true
        if (!result.path("success").asBoolean(false)) {
            // 如果删除未成功，提取错误信息并抛出包含了详细信息的异常
            throw zoteroFailed("Zotero trash item " + safeKey + " failed: " + result.path("error").asText("unknown"));
        }
    }

    /**
     * 获取 Zotero 本地库当前的最新版本号 (Library Version)。
     * 通过查询本地项 (limit=1) 并读取 HTTP 响应头中的 Last-Modified-Version 字段实现。
     * 这用于在后续轮询中，只查询在该版本之后发生变动的条目，以提高效率。
     */
    private long currentLibraryVersion() {
        HttpRequest request = HttpRequest.newBuilder(apiUri(LOCAL_ITEMS_PATH + "?limit=1&format=json"))
                .timeout(properties.getRequestTimeout())
                .header("Zotero-API-Version", "3")
                .GET()
                .build();
        HttpResponse<String> response = send(request);
        if (response.statusCode() != 200) {
            throw zoteroFailed("Zotero local API failed with HTTP " + response.statusCode());
        }
        return response.headers()
                .firstValue("Last-Modified-Version")
                .map(this::parseLongOrZero)
                .orElse(0L);
    }

    /**
     * 轮询等待 Zotero 识别 PDF 并在本地生成元数据记录。
     *
     * @param sourceUrl              当初传入 Zotero 的自定义来源 URL，用于唯一定位刚才导入的 PDF
     * @param previousLibraryVersion 导入 PDF 前的库版本号，缩小检索范围
     * @return 提取并封装好的论文元数据
     */
    private RecognizedResult waitForRecognizedMetadata(
            String sourceUrl,
            long previousLibraryVersion,
            String safeFileName
    ) {
        int maxAttempts = Math.max(1, properties.getMetadataMaxAttempts());
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            // 尝试查找包含我们 sourceUrl 的附件，并获取其所属的父项目 (即 Zotero 为该 PDF 生成的条目)
            ImportedAttachment importedAttachment = findImportedAttachment(sourceUrl, previousLibraryVersion);
            if (importedAttachment != null && StringUtils.hasText(importedAttachment.parentItemKey())) {
                // 如果找到了父项目，说明识别完成，直接读取其元数据
                return new RecognizedResult(true, readParentMetadata(importedAttachment.parentItemKey()),
                        importedAttachment.parentItemKey());
            }
            if (importedAttachment != null && StringUtils.hasText(importedAttachment.attachmentItemKey())) {
                log.warn(
                        "Zotero imported PDF as standalone attachment, fallback to attachment metadata: sourceUrl={}, attachmentItemKey={}",
                        sourceUrl,
                        importedAttachment.attachmentItemKey()
                );
                return new RecognizedResult(false, fallbackAttachmentMetadata(importedAttachment.data(), safeFileName),
                        importedAttachment.attachmentItemKey());
            }
            // 如果还没找到，并且未达到最大重试次数，则休眠等待后继续下一轮轮询
            if (attempt < maxAttempts) {
                sleepBeforeNextMetadataPoll();
            }
        }
        log.warn(
                "Zotero metadata was not available after PDF import, fallback to filename metadata: sourceUrl={}, fileName={}",
                sourceUrl,
                safeFileName
        );
        return new RecognizedResult(false, fallbackFileNameMetadata(safeFileName), null);
    }

    /**
     * 查询在指定库版本之后变动的条目，找到关联特定 sourceUrl 的附件，返回该附件所属的父级条目 Key。
     *
     * @param sourceUrl              保存 PDF 附件时传入的唯一标识链接
     * @param previousLibraryVersion 开始导入操作前的库版本
     * @return 父条目的唯一 Key，如果未找到或尚未生成则返回 null
     */
    private ImportedAttachment findImportedAttachment(String sourceUrl, long previousLibraryVersion) {
        HttpRequest request = HttpRequest.newBuilder(apiUri(LOCAL_ITEMS_PATH
                        + "?since=" + previousLibraryVersion
                        + "&limit=100&format=json"))
                .timeout(properties.getRequestTimeout())
                .header("Zotero-API-Version", "3")
                .GET()
                .build();
        HttpResponse<String> response = send(request);
        if (response.statusCode() != 200) {
            throw zoteroFailed("Zotero local API failed with HTTP " + response.statusCode());
        }
        try {
            JsonNode items = objectMapper.readTree(response.body());
            if (!items.isArray()) {
                return null;
            }
            // 遍历所有最近更新的条目
            for (JsonNode item : items) {
                JsonNode data = item.path("data");
                // 如果该条目是一个附件，且其 url 字段与我们赋予的 sourceUrl 匹配
                if ("attachment".equals(data.path("itemType").asText())
                        && sourceUrl.equals(data.path("url").asText())) {
                    String attachmentItemKey = data.path("key").asText(null);
                    String parentItem = data.path("parentItem").asText(null);
                    return new ImportedAttachment(attachmentItemKey, parentItem, data);
                }
            }
            return null;
        } catch (JsonProcessingException exception) {
            throw zoteroFailed("Zotero local API returned invalid item JSON");
        }
    }

    private ZoteroPaperMetadata fallbackAttachmentMetadata(JsonNode attachmentData, String safeFileName) {
        String title = textOrNull(attachmentData, "title");
        if (!StringUtils.hasText(title) || "PDF".equalsIgnoreCase(title)) {
            title = titleFromFileName(safeFileName);
        }
        return new ZoteroPaperMetadata(
                title,
                List.of(),
                tags(attachmentData.path("tags")),
                defaultLanguageForTitle(textOrNull(attachmentData, "language"), title),
                null,
                null,
                null
        );
    }

    private ZoteroPaperMetadata fallbackFileNameMetadata(String safeFileName) {
        String title = titleFromFileName(safeFileName);
        return new ZoteroPaperMetadata(
                title,
                List.of(),
                List.of(),
                defaultLanguageForTitle(null, title),
                null,
                null,
                null
        );
    }

    /**
     * 通过父级项目的 Key，向 Zotero 请求该条目的完整信息，并转换为系统内部的元数据对象。
     *
     * @param parentItemKey 父级项目（即论文文献）的唯一 Key
     * @return 封装了标题、作者、标签、年份等信息的 ZoteroPaperMetadata 记录
     */
    private ZoteroPaperMetadata readParentMetadata(String parentItemKey) {
        HttpRequest request = HttpRequest.newBuilder(apiUri(LOCAL_ITEMS_PATH + "/" + safeItemKey(parentItemKey) + "?format=json"))
                .timeout(properties.getRequestTimeout())
                .header("Zotero-API-Version", "3")
                .GET()
                .build();
        HttpResponse<String> response = send(request);
        if (response.statusCode() != 200) {
            throw zoteroFailed("Zotero parent item read failed with HTTP " + response.statusCode());
        }
        try {
            JsonNode data = objectMapper.readTree(response.body()).path("data");
            // 提取关键元数据并构建不可变对象返回
            return new ZoteroPaperMetadata(
                    textOrNull(data, "title"),
                    creators(data.path("creators")),
                    tags(data.path("tags")),
                    defaultLanguage(textOrNull(data, "language")),
                    yearFromDate(textOrNull(data, "date")),
                    venue(data),
                    textOrNull(data, "DOI")
            );
        } catch (JsonProcessingException exception) {
            throw zoteroFailed("Zotero parent item response is not valid JSON");
        }
    }

    /**
     * 解析 Zotero 的 creators 数组，提取所有作者的姓名。
     */
    private List<String> creators(JsonNode creators) {
        if (!creators.isArray()) {
            return List.of();
        }
        List<String> authors = new ArrayList<>();
        for (JsonNode creator : creators) {
            // 通常只关心类型为 author 的创作者（排除 editor/translator 等）
            String creatorType = creator.path("creatorType").asText();
            if (StringUtils.hasText(creatorType) && !"author".equals(creatorType)) {
                continue;
            }
            // 尝试获取全名
            String name = textOrNull(creator, "name");
            if (!StringUtils.hasText(name)) {
                // 如果没有全名字段，则尝试拼接 firstName 和 lastName
                name = (creator.path("firstName").asText("") + " " + creator.path("lastName").asText("")).trim();
            }
            if (StringUtils.hasText(name)) {
                authors.add(name);
            }
        }
        return authors;
    }

    /**
     * 解析 Zotero 的 tags 数组，将其转化为简单的字符串关键词列表。
     */
    private List<String> tags(JsonNode tags) {
        if (!tags.isArray()) {
            return List.of();
        }
        List<String> keywords = new ArrayList<>();
        for (JsonNode tag : tags) {
            String keyword = textOrNull(tag, "tag");
            if (StringUtils.hasText(keyword)) {
                keywords.add(keyword);
            }
        }
        return keywords;
    }

    /**
     * 利用正则表达式从 Zotero 给出的可能较为复杂的日期字符串（如 "2023-05-12" 或 "May 2023"）中提取 4 位数字作为年份。
     */
    private Integer yearFromDate(String date) {
        if (!StringUtils.hasText(date)) {
            return null;
        }
        Matcher matcher = YEAR_PATTERN.matcher(date);
        if (!matcher.find()) {
            return null;
        }
        return Integer.parseInt(matcher.group(1));
    }

    /**
     * 从不同文献类型可能存在的字段中，查找发表来源（如期刊名称、会议名称、出版社等）。
     * 根据优先级依次回退尝试。
     */
    private String venue(JsonNode data) {
        // 按优先级排列的潜在来源字段
        List<String> fields = List.of(
                "publicationTitle",
                "conferenceName",
                "proceedingsTitle",
                "bookTitle",
                "websiteTitle",
                "publisher"
        );
        for (String field : fields) {
            String value = textOrNull(data, field);
            if (StringUtils.hasText(value)) {
                return value;
            }
        }
        return null;
    }

    /**
     * 安全提取 JSON 节点中指定名称字段的文本内容。如果内容为空或只含空格，则返回 null。
     */
    private String textOrNull(JsonNode node, String fieldName) {
        String value = node.path(fieldName).asText(null);
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    /**
     * 获取文档语言，如果没有记录则默认返回 "en" (英文)。
     */
    private String defaultLanguage(String language) {
        return StringUtils.hasText(language) ? language : "en";
    }

    private String defaultLanguageForTitle(String language, String title) {
        if (StringUtils.hasText(language)) {
            return language;
        }
        return containsCjk(title) ? "zh" : "en";
    }

    private boolean containsCjk(String value) {
        if (!StringUtils.hasText(value)) {
            return false;
        }
        return value.codePoints().anyMatch(codePoint ->
                (codePoint >= 0x4E00 && codePoint <= 0x9FFF)
                        || (codePoint >= 0x3400 && codePoint <= 0x4DBF)
                        || (codePoint >= 0x20000 && codePoint <= 0x2A6DF));
    }

    /**
     * 安全将字符串转为 long，用于解析 HTTP Header 中的版本号。若解析失败则回退至 0。
     */
    private long parseLongOrZero(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            return 0L;
        }
    }

    /**
     * 根据配置的间隔时间 (metadataPollInterval) 暂停当前线程，避免高频请求压垮本地 API。
     */
    private void sleepBeforeNextMetadataPoll() {
        if (properties.getMetadataPollInterval().isZero() || properties.getMetadataPollInterval().isNegative()) {
            return;
        }
        try {
            Thread.sleep(properties.getMetadataPollInterval().toMillis());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw zoteroFailed("Zotero metadata polling interrupted");
        }
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

    private String writeHeaderJson(Object value) {
        try {
            return objectMapper.writer()
                    .with(JsonGenerator.Feature.ESCAPE_NON_ASCII)
                    .writeValueAsString(value);
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
    private String safeItemKey(String itemKey) {
        if (!StringUtils.hasText(itemKey)) {
            throw zoteroFailed("Zotero item key is empty");
        }
        return itemKey.replaceAll("[^A-Za-z0-9]", "");
    }

    /**
     * 查询父项目所属的第一个 Zotero 分类（集合）的名称。
     */
    private String lookupFirstCollectionName(String parentItemKey) {
        if (!StringUtils.hasText(parentItemKey)) {
            return null;
        }
        try {
            String itemJson = fetchItem(parentItemKey);
            JsonNode data = objectMapper.readTree(itemJson).path("data");
            JsonNode collections = data.path("collections");
            if (!collections.isArray() || collections.isEmpty()) {
                return null;
            }
            String firstCollectionKey = collections.get(0).asText(null);
            if (!StringUtils.hasText(firstCollectionKey)) {
                return null;
            }
            return fetchCollectionName(firstCollectionKey);
        } catch (Exception e) {
            return null;
        }
    }

    private String fetchItem(String itemKey) {
        HttpRequest request = HttpRequest.newBuilder(apiUri(LOCAL_ITEMS_PATH + "/" + safeItemKey(itemKey) + "?format=json"))
                .timeout(properties.getRequestTimeout())
                .header("Zotero-API-Version", "3")
                .GET()
                .build();
        HttpResponse<String> response = send(request);
        if (response.statusCode() != 200) {
            return null;
        }
        return response.body();
    }

    private String fetchCollectionName(String collectionKey) {
        HttpRequest request = HttpRequest.newBuilder(apiUri("/api/users/0/collections/" + safeItemKey(collectionKey) + "?format=json"))
                .timeout(properties.getRequestTimeout())
                .header("Zotero-API-Version", "3")
                .GET()
                .build();
        HttpResponse<String> response = send(request);
        if (response.statusCode() != 200) {
            return null;
        }
        try {
            JsonNode data = objectMapper.readTree(response.body()).path("data");
            return textOrNull(data, "name");
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private record ImportedAttachment(String attachmentItemKey, String parentItemKey, JsonNode data) {
    }

    private record RecognizedResult(boolean canRecognize, ZoteroPaperMetadata metadata, String itemKey) {
    }

    private BusinessException zoteroFailed(String message) {
        return new BusinessException(ErrorCode.ZOTERO_WRITE_FAILED, message);
    }
}
