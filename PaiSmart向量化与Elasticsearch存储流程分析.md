# PaiSmart 向量化与 Elasticsearch 存储流程分析

## 一、整体架构概览

PaiSmart 采用 **异步消息驱动** 的文档处理流水线，将用户上传的文件经过"文本提取 → 语义分块 → 向量化 → Elasticsearch 存储"四个步骤，最终支撑 RAG（检索增强生成）场景下的语义搜索。

```
用户上传文件 → MinIO 对象存储 → Kafka 消息 → 消费者异步处理
                                                    │
                                    ┌───────────────┼───────────────┐
                                    ▼                               ▼
                              ParseService                   VectorizationService
                           (Apache Tika 解析              (EmbeddingClient 调
                            + HanLP 分词分块)              通义千问 API 生成向量)
                                    │                               │
                                    ▼                               ▼
                              MySQL 存储                      Elasticsearch
                           (document_vectors)             (knowledge_base 索引)
```

---

## 二、核心数据结构

### 2.1 MySQL 中间存储：`DocumentVector`

文件 [DocumentVector.java](src/main/java/com/yizhaoqi/smartpai/model/DocumentVector.java) — JPA 实体，映射到 `document_vectors` 表，存放分块后的**纯文本**（不含向量）：

```java
@Data
@Entity
@Table(name = "document_vectors")
public class DocumentVector {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long vectorId;          // 自增主键

    @Column(nullable = false, length = 32)
    private String fileMd5;         // 文件 MD5 指纹

    @Column(nullable = false)
    private Integer chunkId;        // 分块序号（从 1 开始递增）

    @Lob
    private String textContent;     // 分块文本内容（可存大文本）

    @Column(length = 32)
    private String modelVersion;    // 模型版本标识

    @Column(nullable = false, name = "user_id", length = 64)
    private String userId;          // 上传用户 ID（多租户权限）

    @Column(name = "org_tag", length = 50)
    private String orgTag;          // 组织标签（多租户隔离）

    @Column(name = "is_public", nullable = false)
    private boolean isPublic;       // 是否公开
}
```

**关键设计**：MySQL 中的 `document_vectors` 表**只存储文本内容**，向量数据仅存在于 Elasticsearch。这是一种"文本留底 + 向量上索引"的分离策略。

### 2.2 Elasticsearch 文档：`EsDocument`

文件 [EsDocument.java](src/main/java/com/yizhaoqi/smartpai/entity/EsDocument.java) — POJO，映射到 ES 索引 `knowledge_base`：

```java
@Data
public class EsDocument {
    private String id;              // UUID，用作 ES 文档 _id（保证幂等覆盖写）
    private String fileMd5;         // 文件指纹
    private Integer chunkId;        // 分块序号
    private String textContent;     // 分块文本内容
    private float[] vector;         // 2048 维向量（dense_vector）
    private String modelVersion;    // 向量模型版本
    private String userId;          // 上传用户 ID
    private String orgTag;          // 组织标签
    private boolean isPublic;       // 是否公开
}
```

**ES 索引映射**（`knowledge_base`）：

| 字段 | ES 类型 | 说明 |
|------|---------|------|
| `_id` | — (使用 `id` 字段值) | UUID，可通过相同 ID 覆盖写入 |
| `fileMd5` | text/keyword | 文件指纹，用于按文件删除 |
| `chunkId` | integer | 分块序号 |
| `textContent` | text | 分块原始文本，支持全文搜索 |
| `vector` | float[] (实际应设 `dense_vector`, dim=2048) | 语义向量 |
| `modelVersion` | keyword | 模型标识 |
| `userId` | keyword | 用户 ID |
| `orgTag` | keyword | 组织标签 |
| `isPublic` | boolean | 公开标记 |

> **注意**：项目已通过 `EsIndexInitializer` 在启动时自动创建显式 mapping（见下方"六、Elasticsearch 配置详解"），`vector` 字段已正确设为 `dense_vector`（dimension: 2048, similarity: cosine），支持 kNN 向量检索。

### 2.3 数据传输对象

**TextChunk**（[TextChunk.java](src/main/java/com/yizhaoqi/smartpai/entity/TextChunk.java)）—— VectorizationService 内部使用的轻量 DTO：

```java
@Setter @Getter
public class TextChunk {
    private int chunkId;        // 分块序号
    private String content;     // 分块文本内容
}
```

**FileProcessingTask**（[FileProcessingTask.java](src/main/java/com/yizhaoqi/smartpai/model/FileProcessingTask.java)）—— Kafka 消息体：

```java
@Data
@AllArgsConstructor @NoArgsConstructor
public class FileProcessingTask {
    private String fileMd5;     // 文件 MD5
    private String filePath;    // 存储路径（MinIO 或本地路径）
    private String fileName;    // 文件名
    private String userId;      // 上传用户
    private String orgTag;      // 组织标签
    private boolean isPublic;   // 是否公开
}
```

---

## 三、从文件上传到 Elasticsearch 的完整步骤

### Step 1 — 文件分片上传（UploadController）

`POST /api/v1/upload/chunk` 接收前端分片上传的文件块，验证文件类型后存入 MinIO。

> 文件 [UploadController.java:67-159](src/main/java/com/yizhaoqi/smartpai/controller/UploadController.java#L67-L159)

### Step 2 — 合并分片并发送 Kafka 消息（UploadController.mergeFile）

前端调用 `POST /api/v1/upload/merge` 后，服务器合并所有分片为完整文件，然后在**事务内**将 `FileProcessingTask` 投递到 Kafka：

```java
// UploadController.java:284-298
FileProcessingTask task = new FileProcessingTask(
        request.fileMd5(),
        objectUrl,
        request.fileName(),
        fileUpload.getUserId(),
        fileUpload.getOrgTag(),
        fileUpload.isPublic()
);

kafkaTemplate.executeInTransaction(kt -> {
    kt.send(kafkaConfig.getFileProcessingTopic(), task);
    return true;
});
```

**Kafka 配置**（[KafkaConfig.java](src/main/java/com/yizhaoqi/smartpai/config/KafkaConfig.java)）：

- **Topic**: `file-processing-topic1`
- **消费者组**: `file-processing-group`
- **Producer 可靠性**: `acks=all`, `enable.idempotence=true`, `retries=3`
- **Consumer 重试**: 固定间隔 3s，最多 4 次重试（共 5 次尝试）
- **死信队列**: 重试耗尽后路由到 `file-processing-dlt`

### Step 3 — Kafka 消费者接收任务（FileProcessingConsumer）

`FileProcessingConsumer` 监听 Topic，执行三步流水线：

```java
// FileProcessingConsumer.java:36-63
@KafkaListener(topics = "#{kafkaConfig.getFileProcessingTopic()}",
               groupId = "#{kafkaConfig.getFileProcessingGroupId()}")
public void processTask(FileProcessingTask task) {
    // 1. 下载文件（支持本地路径和远程 HTTP URL）
    fileStream = downloadFileFromStorage(task.getFilePath());

    // 2. 解析文件 + 语义分块
    parseService.parseAndSave(task.getFileMd5(), fileStream,
            task.getUserId(), task.getOrgTag(), task.isPublic());

    // 3. 向量化 + Elasticsearch 存储
    vectorizationService.vectorize(task.getFileMd5(),
            task.getUserId(), task.getOrgTag(), task.isPublic());
}
```

### Step 4 — 文本提取与语义分块（ParseService）

`ParseService` 是分块逻辑的核心，采用**"父文档-子切片"**的两级策略。

#### 4.1 流式解析（Apache Tika）

```java
// ParseService.java:59-83
public void parseAndSave(String fileMd5, InputStream fileStream,
        String userId, String orgTag, boolean isPublic) {
    checkMemoryThreshold();  // 堆内存 > 80% 时触发 GC，仍超则抛异常

    try (BufferedInputStream bufferedStream = new BufferedInputStream(fileStream, bufferSize)) {
        StreamingContentHandler handler =
            new StreamingContentHandler(fileMd5, userId, orgTag, isPublic);
        AutoDetectParser parser = new AutoDetectParser();
        parser.parse(bufferedStream, handler, metadata, context);
    }
}
```

`StreamingContentHandler` 是内部类，继承 `BodyContentHandler`，重写了 `characters()` 方法：

```java
// ParseService.java:133-178
private class StreamingContentHandler extends BodyContentHandler {
    private final StringBuilder buffer = new StringBuilder();

    @Override
    public void characters(char[] ch, int start, int length) {
        buffer.append(ch, start, length);
        if (buffer.length() >= parentChunkSize) {   // 默认 1MB
            processParentChunk();                    // 触发父块切分
        }
    }

    @Override
    public void endDocument() {
        if (buffer.length() > 0) {
            processParentChunk();   // 处理尾部剩余内容
        }
    }
}
```

#### 4.2 三级语义分块算法

配置参数（[application.yml:73-77](src/main/resources/application.yml#L73-L77)）：

```yaml
file:
  parsing:
    chunk-size: 512         # 每个子块最大字符数
    buffer-size: 8192       # 读取缓冲区 8KB
    max-memory-threshold: 0.8  # 80% 内存阈值
```

分块逻辑 `splitTextIntoChunksWithSemantics()` 采用**三级降级**策略：

```
第一级：按段落分割 → 遇到 \n\n+ 即断
    ↓ （某段落超长）
第二级：按句子分割 → 遇到 。！？；.!?; 即断
    ↓ （某句子超长）
第三级：HanLP 标准分词 → 按词边界切割
    ↓ （HanLP 异常）
兜底级：按字符强制分割
```

关键代码（[ParseService.java:212-257](src/main/java/com/yizhaoqi/smartpai/service/ParseService.java#L212-L257)）：

```java
private List<String> splitTextIntoChunksWithSemantics(String text, int chunkSize) {
    // 第一级：按段落（\n\n+）分割
    String[] paragraphs = text.split("\n\n+");
    StringBuilder currentChunk = new StringBuilder();

    for (String paragraph : paragraphs) {
        if (paragraph.length() > chunkSize) {
            // 保存当前 chunk，然后按句子分割长段落
            chunks.addAll(splitLongParagraph(paragraph, chunkSize));
        } else if (currentChunk.length() + paragraph.length() > chunkSize) {
            chunks.add(currentChunk.toString().trim());
            currentChunk = new StringBuilder(paragraph);
        } else {
            currentChunk.append(paragraph);
        }
    }
    // ... 处理尾部
}
```

句子级分割（[ParseService.java:262-293](src/main/java/com/yizhaoqi/smartpai/service/ParseService.java#L262-L293)）：

```java
private List<String> splitLongParagraph(String paragraph, int chunkSize) {
    // 按中文/英文句子终止符分割
    String[] sentences = paragraph.split("(?<=[。！？；])|(?<=[.!?;])\\s+");
    // 对超长句子再调 splitLongSentence() 用 HanLP 分词
}
```

HanLP 智能分词（[ParseService.java:298-331](src/main/java/com/yizhaoqi/smartpai/service/ParseService.java#L298-L331)）：

```java
private List<String> splitLongSentence(String sentence, int chunkSize) {
    List<Term> termList = StandardTokenizer.segment(sentence);  // HanLP 分词
    for (Term term : termList) {
        String word = term.word;
        if (currentChunk.length() + word.length() > chunkSize && !currentChunk.isEmpty()) {
            chunks.add(currentChunk.toString());
            currentChunk = new StringBuilder();
        }
        currentChunk.append(word);
    }
}
```

#### 4.3 分块结果持久化到 MySQL

```java
// ParseService.java:191-207
private int saveChildChunks(String fileMd5, List<String> chunks,
        String userId, String orgTag, boolean isPublic, int startingChunkId) {
    int currentChunkId = startingChunkId;
    for (String chunk : chunks) {
        currentChunkId++;
        var vector = new DocumentVector();
        vector.setFileMd5(fileMd5);
        vector.setChunkId(currentChunkId);
        vector.setTextContent(chunk);
        vector.setUserId(userId);
        vector.setOrgTag(orgTag);
        vector.setPublic(isPublic);
        documentVectorRepository.save(vector);
    }
    return currentChunkId;
}
```

### Step 5 — 向量化（VectorizationService + EmbeddingClient）

#### 5.1 向量化编排（VectorizationService）

```java
// VectorizationService.java:39-81
public void vectorize(String fileMd5, String userId, String orgTag, boolean isPublic) {
    // 1. 从 MySQL 读取该文件的所有分块文本
    List<TextChunk> chunks = fetchTextChunks(fileMd5);

    // 2. 提取纯文本列表
    List<String> texts = chunks.stream()
            .map(TextChunk::getContent)
            .toList();

    // 3. 调用外部 Embedding API 生成向量（批量）
    List<float[]> vectors = embeddingClient.embed(texts);

    // 4. 组装 EsDocument 列表
    List<EsDocument> esDocuments = IntStream.range(0, chunks.size())
            .mapToObj(i -> new EsDocument(
                    UUID.randomUUID().toString(),  // ES 文档 ID
                    fileMd5,
                    chunks.get(i).getChunkId(),
                    chunks.get(i).getContent(),
                    vectors.get(i),                // 2048 维 float[]
                    "deepseek-embed",              // modelVersion（注意：实际调的是通义千问）
                    userId, orgTag, isPublic
            ))
            .toList();

    // 5. 批量写入 Elasticsearch
    elasticsearchService.bulkIndex(esDocuments);
}
```

从 MySQL 读取分块（[VectorizationService.java:90-101](src/main/java/com/yizhaoqi/smartpai/service/VectorizationService.java#L90-L101)）：

```java
private List<TextChunk> fetchTextChunks(String fileMd5) {
    List<DocumentVector> vectors = documentVectorRepository.findByFileMd5(fileMd5);
    return vectors.stream()
            .map(vector -> new TextChunk(vector.getChunkId(), vector.getTextContent()))
            .toList();
}
```

#### 5.2 Embedding API 调用（EmbeddingClient）

**API 配置**（[application.yml:107-113](src/main/resources/application.yml#L107-L113)）：

```yaml
embedding:
  api:
    url: https://dashscope.aliyuncs.com/compatible-mode/v1   # 阿里云 DashScope
    key: sk-xxxxxxxxxxx
    model: text-embedding-v4    # 通义千问 Embedding v4
    batch-size: 10              # DashScope 单次限制 10 条
    dimension: 2048             # 输出向量维度
```

**WebClient 配置**（[WebClientConfig.java](src/main/java/com/yizhaoqi/smartpai/config/WebClientConfig.java)）：

```java
@Bean
public WebClient embeddingWebClient() {
    ExchangeStrategies strategies = ExchangeStrategies.builder()
        .codecs(configurer -> configurer.defaultCodecs()
            .maxInMemorySize(16 * 1024 * 1024))  // 16MB 响应缓冲
        .build();

    return WebClient.builder()
        .baseUrl(apiUrl)
        .exchangeStrategies(strategies)
        .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
        .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
        .build();
}
```

**批量调用**（[EmbeddingClient.java:70-129](src/main/java/com/yizhaoqi/smartpai/client/EmbeddingClient.java#L70-L129)）：

```java
public List<float[]> embed(List<String> texts) {
    List<float[]> all = new ArrayList<>(texts.size());

    // 分批处理，每次最多 batchSize（10）条
    for (int start = 0; start < texts.size(); start += batchSize) {
        int end = Math.min(start + batchSize, texts.size());
        List<String> sub = texts.subList(start, end);

        String response = callApiOnce(sub);      // HTTP POST 调用
        all.addAll(parseVectors(response));      // 解析返回的向量
    }
    return all;
}
```

**单次 API 调用**（[EmbeddingClient.java:136-172](src/main/java/com/yizhaoqi/smartpai/client/EmbeddingClient.java#L136-L172)）：

```java
private String callApiOnce(List<String> batch) {
    Map<String, Object> requestBody = new HashMap<>();
    requestBody.put("model", modelId);              // "text-embedding-v4"
    requestBody.put("input", batch);                 // 文本数组
    requestBody.put("dimension", dimension);         // 2048
    requestBody.put("encoding_format", "float");     // 返回 float 数组

    return webClient.post()
            .uri("/embeddings")
            .bodyValue(requestBody)
            .retrieve()
            .bodyToMono(String.class)
            .retryWhen(Retry.fixedDelay(3, Duration.ofSeconds(1))  // 重试 3 次
                    .filter(e -> e instanceof WebClientResponseException))
            .block(Duration.ofSeconds(30));   // 阻塞等待，30s 超时
}
```

实际发送的 HTTP 请求格式（OpenAI 兼容风格）：

```json
POST https://dashscope.aliyuncs.com/compatible-mode/v1/embeddings
Authorization: Bearer sk-xxxxxxxxxxx
Content-Type: application/json

{
  "model": "text-embedding-v4",
  "input": ["文本1", "文本2", "..."],
  "dimension": 2048,
  "encoding_format": "float"
}
```

**响应解析**（[EmbeddingClient.java:180-218](src/main/java/com/yizhaoqi/smartpai/client/EmbeddingClient.java#L180-L218)）：

```java
private List<float[]> parseVectors(String response) throws Exception {
    JsonNode jsonNode = objectMapper.readTree(response);
    JsonNode data = jsonNode.get("data");  // {"data": [{"embedding": [...]}, ...]}

    List<float[]> vectors = new ArrayList<>();
    for (JsonNode item : data) {
        JsonNode embedding = item.get("embedding");
        if (embedding != null && embedding.isArray()) {
            float[] vector = new float[embedding.size()];  // 2048
            for (int i = 0; i < embedding.size(); i++) {
                vector[i] = (float) embedding.get(i).asDouble();
            }
            vectors.add(vector);
        }
    }
    return vectors;
}
```

API 返回的 JSON 结构：

```json
{
  "data": [
    {"embedding": [0.0123, -0.0456, 0.0789, ...]},   // 第 1 条文本的 2048 维向量
    {"embedding": [-0.0234, 0.0567, -0.0890, ...]},   // 第 2 条文本的 2048 维向量
    ...
  ]
}
```

### Step 6 — 批量写入 Elasticsearch（ElasticsearchService）

#### 6.1 ES 客户端配置

[EsConfig.java](src/main/java/com/yizhaoqi/smartpai/config/EsConfig.java) 使用 `co.elastic.clients`（ES Java Client 8.x）：

```java
@Bean
public ElasticsearchClient elasticsearchClient() {
    RestClientBuilder builder = RestClient.builder(
        new HttpHost(host, port, scheme));   // localhost:9200, https

    // Basic Auth 认证
    BasicCredentialsProvider credsProvider = new BasicCredentialsProvider();
    credsProvider.setCredentials(AuthScope.ANY,
        new UsernamePasswordCredentials(username, password));

    // 开发环境：信任所有 TLS 证书
    SSLContext sslContext = SSLContexts.custom()
        .loadTrustMaterial(null, (chain, authType) -> true)
        .build();

    // 返回高级客户端
    ElasticsearchTransport transport =
        new RestClientTransport(restClient, new JacksonJsonpMapper());
    return new ElasticsearchClient(transport);
}
```

#### 6.2 Bulk 批量索引

```java
// ElasticsearchService.java:40-81
public void bulkIndex(List<EsDocument> documents) {
    // 1. 将每个 EsDocument 映射为 BulkOperation (index 指令)
    List<BulkOperation> bulkOperations = documents.stream()
            .map(doc -> BulkOperation.of(op -> op.index(idx -> idx
                    .index("knowledge_base")  // 目标索引名
                    .id(doc.getId())          // 文档 _id = UUID（幂等覆盖写）
                    .document(doc)            // 文档体（自动序列化为 JSON）
            )))
            .toList();

    // 2. 构建 BulkRequest
    BulkRequest request = BulkRequest.of(b -> b.operations(bulkOperations));

    // 3. 执行批量写入
    BulkResponse response = esClient.bulk(request);

    // 4. 检查是否有部分失败
    if (response.errors()) {
        for (BulkResponseItem item : response.items()) {
            if (item.error() != null) {
                logger.error("文档索引失败 - ID: {}, 错误: {}",
                    item.id(), item.error().reason());
            }
        }
        throw new RuntimeException("批量索引部分失败");
    }
}
```

#### 6.3 按文件删除

```java
// ElasticsearchService.java:94-107
public void deleteByFileMd5(String fileMd5) {
    DeleteByQueryRequest request = DeleteByQueryRequest.of(d -> d
            .index("knowledge_base")
            .query(q -> q.term(t -> t.field("fileMd5").value(fileMd5)))
    );
    esClient.deleteByQuery(request);
}
```

---

## 四、完整数据流总结

```
┌─────────────────────────────────────────────────────────┐
│  1. 文件上传                                              │
│  POST /api/v1/upload/chunk  →  分片存入 MinIO             │
├─────────────────────────────────────────────────────────┤
│  2. 文件合并 + 发送 Kafka                                 │
│  POST /api/v1/upload/merge                              │
│  → 合并分片为完整文件                                      │
│  → 事务内发送 FileProcessingTask 到 file-processing-topic1│
├─────────────────────────────────────────────────────────┤
│  3. Kafka Consumer 消费                                   │
│  FileProcessingConsumer.processTask()                    │
│  → downloadFileFromStorage() 下载文件流                    │
├─────────────────────────────────────────────────────────┤
│  4. 文本提取 + 语义分块 (ParseService)                     │
│  → Apache Tika 流式解析文本                                │
│  → 父块缓冲区 (1MB) → 子块分割 (512 字符)                  │
│  → 三级语义分割：段落 → 句子 → HanLP 分词                  │
│  → 结果保存到 MySQL document_vectors 表                    │
├─────────────────────────────────────────────────────────┤
│  5. 向量化 (VectorizationService)                         │
│  → 从 MySQL 读取分块文本                                   │
│  → EmbeddingClient.embed() 分批调用 API                   │
│  → POST dashscope.aliyuncs.com/compatible-mode/v1/embeddings│
│  → 模型: text-embedding-v4, 维度: 2048                    │
│  → 返回 float[2048] 向量数组                              │
├─────────────────────────────────────────────────────────┤
│  6. Elasticsearch 存储 (ElasticsearchService)             │
│  → 组装 EsDocument (UUID, fileMd5, chunkId, text, vector) │
│  → Bulk API 批量写入 knowledge_base 索引                  │
│  → 相同 UUID 可覆盖写（幂等）                              │
└─────────────────────────────────────────────────────────┘
```

**关键数字**：

| 参数 | 值 | 来源 |
|------|-----|------|
| Embedding 模型 | `text-embedding-v4` (阿里云 DashScope 通义千问) | application.yml |
| 向量维度 | 2048 | application.yml |
| API 批处理大小 | 10 条/次 | application.yml |
| 子块最大字符数 | 512 | application.yml |
| 父块缓冲区 | 1MB | application.yml |
| ES 索引名 | `knowledge_base` | ElasticsearchService.java |
| MySQL 表名 | `document_vectors` | DocumentVector.java |
| Kafka Topic | `file-processing-topic1` | application.yml |
| 死信队列 | `file-processing-dlt` | application.yml |
| 重试策略 | 固定间隔 3s，最多 4 次 | KafkaConfig.java |

---

## 五、注意事项

1. **向量不存 MySQL**：MySQL `document_vectors` 表仅存储文本，向量数据只存在于 Elasticsearch。如需重建索引，需重新调用 Embedding API。

2. **modelVersion 字段语义不一致**：[VectorizationService.java:67](src/main/java/com/yizhaoqi/smartpai/service/VectorizationService.java#L67) 将 `modelVersion` 硬编码为 `"deepseek-embed"`，但实际 API 调用的是阿里云通义千问 `text-embedding-v4`。这是历史遗留问题。

3. **ES 显式映射**：项目已在 `EsIndexInitializer` 中通过 `knowledge_base.json` 显式创建索引 mapping，`vector` 字段已正确声明为 `dense_vector`（dimension: 2048, similarity: cosine），支持 kNN 向量检索。但 [VectorizationService.java:67](src/main/java/com/yizhaoqi/smartpai/service/VectorizationService.java#L67) 硬编码的 `modelVersion = "deepseek-embed"` 与实际使用的通义千问模型不一致。

4. **权限字段端到端传递**：`userId`、`orgTag`、`isPublic` 三个字段从上传请求开始，经 Kafka 消息、MySQL 存储、API 调用，最终写入 ES 文档，用于实现多租户文档级访问控制。

5. **内存安全**：ParseService 使用流式解析 + 父块缓冲区（1MB）+ 内存阈值检测（80% 触发 GC），防止大文件导致 OOM。

---

## 六、Elasticsearch 配置详解

### 6.1 yml 连接配置

[application.yml:79-84](src/main/resources/application.yml#L79-L84)：

```yaml
elasticsearch:
  host: localhost       # ES 主机地址
  port: 9200            # ES 端口号
  scheme: https         # 协议（http/https）
  username: elastic     # 认证用户名
  password: sz6ubmhMscjkqefxgOCz  # 认证密码
```

**注意**：yml 中只配置了连接参数，**没有 `index-name` 配置项**。索引名 `knowledge_base` 是硬编码在 Java 代码中的。

### 6.2 ES 客户端初始化（EsConfig）

文件 [EsConfig.java](src/main/java/com/yizhaoqi/smartpai/config/EsConfig.java)：

```java
@Configuration
public class EsConfig {
    @Value("${elasticsearch.host}")   private String host;
    @Value("${elasticsearch.port}")   private int port;
    @Value("${elasticsearch.scheme}") private String scheme;
    @Value("${elasticsearch.username}") private String username;
    @Value("${elasticsearch.password}") private String password;

    @Bean
    public ElasticsearchClient elasticsearchClient() {
        // 1. 构建低级 REST 客户端
        RestClientBuilder builder = RestClient.builder(
            new HttpHost(host, port, scheme));   // localhost:9200, https

        // 2. HTTP Basic Auth 认证
        BasicCredentialsProvider credsProvider = new BasicCredentialsProvider();
        credsProvider.setCredentials(AuthScope.ANY,
            new UsernamePasswordCredentials(username, password));

        // 3. 开发环境：信任所有 TLS 证书（生产需替换为真实证书）
        SSLContext sslContext = SSLContexts.custom()
            .loadTrustMaterial(null, (chain, authType) -> true)
            .build();

        // 4. 组装高级客户端
        ElasticsearchTransport transport =
            new RestClientTransport(restClient, new JacksonJsonpMapper());
        return new ElasticsearchClient(transport);
    }
}
```

技术栈：`co.elastic.clients`（Elasticsearch Java Client 8.x），底层通过 `RestClientTransport` + `JacksonJsonpMapper` 通信。

### 6.3 索引初始化（EsIndexInitializer）

文件 [EsIndexInitializer.java](src/main/java/com/yizhaoqi/smartpai/config/EsIndexInitializer.java) 实现了 `CommandLineRunner`，在 Spring Boot 启动后自动执行：

```java
@Component
public class EsIndexInitializer implements CommandLineRunner {

    @Value("classpath:es-mappings/knowledge_base.json")
    private Resource mappingResource;

    @Override
    public void run(String... args) throws Exception {
        // 1. 检查索引是否已存在
        BooleanResponse exists = esClient.indices()
            .exists(ExistsRequest.of(e -> e.index("knowledge_base")));

        if (!exists.value()) {
            // 2. 不存在则创建，加载 JSON mapping 定义
            String mappingJson = Files.readString(mappingResource.getFile().toPath());
            CreateIndexRequest request = CreateIndexRequest.of(c -> c
                .index("knowledge_base")
                .withJson(new StringReader(mappingJson))
            );
            esClient.indices().create(request);
        }
    }
}
```

流程：`启动 → 检查 knowledge_base 索引是否存在 → 不存在则读取 mapping JSON 创建`

### 6.4 显式索引 Mapping

文件 [knowledge_base.json](src/main/resources/es-mappings/knowledge_base.json)：

```json
{
  "mappings": {
    "properties": {
      "fileMd5":      { "type": "keyword" },
      "chunkId":      { "type": "integer" },
      "textContent":  {
        "type": "text",
        "analyzer": "ik_max_word",
        "search_analyzer": "ik_smart"
      },
      "vector": {
        "type": "dense_vector",
        "dims": 2048,
        "index": true,
        "similarity": "cosine"
      },
      "modelVersion": { "type": "keyword" },
      "userId":       { "type": "keyword" },
      "orgTag":       { "type": "keyword" },
      "isPublic":     { "type": "boolean" }
    }
  }
}
```

各字段用途说明：

| 字段 | ES 类型 | 配置细节 | 用途 |
|------|---------|----------|------|
| `fileMd5` | `keyword` | 精确匹配，不分词 | 按文件删除、按文件过滤 |
| `chunkId` | `integer` | 数值类型 | 标识分块顺序 |
| `textContent` | `text` | `ik_max_word` 索引 / `ik_smart` 搜索 | 中文全文搜索 |
| `vector` | `dense_vector` | dims=2048, cosine 相似度, 建立索引 | kNN 语义向量检索 |
| `modelVersion` | `keyword` | 精确匹配 | 模型版本追踪 |
| `userId` | `keyword` | 精确匹配 | 多租户权限过滤 |
| `orgTag` | `keyword` | 精确匹配 | 组织级别权限隔离 |
| `isPublic` | `boolean` | true/false | 公开/私有过滤 |

> `vector` 字段的 `"index": true` 表示启用 HNSW 索引，配合 `similarity: cosine` 支持高效的 kNN 近似搜索。

---

## 七、如何删除数据

### 7.1 现有方法：按文件 MD5 删除

`ElasticsearchService` 已提供 `deleteByFileMd5()` 方法（[ElasticsearchService.java:94-107](src/main/java/com/yizhaoqi/smartpai/service/ElasticsearchService.java#L94-L107)）：

```java
public void deleteByFileMd5(String fileMd5) {
    try {
        // 构造 Delete By Query 请求：在 knowledge_base 索引中
        // 按 fileMd5 精确匹配（term query）批量删除
        DeleteByQueryRequest request = DeleteByQueryRequest.of(d -> d
                .index("knowledge_base")
                .query(q -> q.term(t -> t.field("fileMd5").value(fileMd5)))
        );
        esClient.deleteByQuery(request);
    } catch (Exception e) {
        throw new RuntimeException("删除文档失败", e);
    }
}
```

原理：ES 的 **Delete By Query** API —— 先查询匹配的文档，再批量删除。对于一个文件的所有分块（共享同一个 `fileMd5`），一次调用全部删除。

**调用方式**：
```java
// 在业务代码中注入 ElasticsearchService 后调用
elasticsearchService.deleteByFileMd5("文件的MD5值");
```

### 7.2 需要补充的删除方法

目前项目缺少以下删除场景，可参照 `deleteByFileMd5()` 的模板在 `ElasticsearchService` 中添加：

**按用户 ID 删除**（用户注销时清理）：
```java
public void deleteByUserId(String userId) {
    DeleteByQueryRequest request = DeleteByQueryRequest.of(d -> d
            .index("knowledge_base")
            .query(q -> q.term(t -> t.field("userId").value(userId)))
    );
    esClient.deleteByQuery(request);
}
```

**按组织标签删除**（组织解散时清理）：
```java
public void deleteByOrgTag(String orgTag) {
    DeleteByQueryRequest request = DeleteByQueryRequest.of(d -> d
            .index("knowledge_base")
            .query(q -> q.term(t -> t.field("orgTag").value(orgTag)))
    );
    esClient.deleteByQuery(request);
}
```

**销毁整个索引**（极少使用，会删除所有知识库数据）：
```java
public void dropIndex() {
    esClient.indices().delete(d -> d.index("knowledge_base"));
}
```

**按 ID 删除单条文档**：
```java
public void deleteById(String documentId) {
    esClient.delete(d -> d.index("knowledge_base").id(documentId));
}
```

### 7.3 删除流程图

```
业务层调用
    │
    ▼
ElasticsearchService.deleteByFileMd5(fileMd5)
    │
    ▼
Delete By Query API  ──→  POST knowledge_base/_delete_by_query
    │                     { "query": { "term": { "fileMd5": "xxx" } } }
    ▼
ES 内部：先查询匹配文档 → 再逐文档删除 → 返回删除数量
```

---

## 八、如何创建另外的知识库

### 8.1 现状：索引名硬编码

当前 `"knowledge_base"` 字符串硬编码在以下位置：

| 文件 | 行号 | 对应操作 |
|------|------|----------|
| `EsIndexInitializer.java` | 58, 76 | 检查索引存在 + 创建索引 |
| `ElasticsearchService.java` | 49, 98 | `bulkIndex()` 写入 + `deleteByFileMd5()` 删除 |
| `HybridSearchService.java` | 89, 212, 319, 377 | `searchWithPermission()`、`textOnlySearch()` 等 4 个搜索方法 |
| `MinioMigrationUtil.java` | 172 | 迁移工具中的索引引用 |

**yml 中没有索引名配置**，原因纯粹是开发时图方便写死了，不是架构设计。

### 8.2 方案一：同一索引内用字段区分（推荐，改动最小）

在 `EsDocument` 和 `knowledge_base.json` mapping 中各加一个字段，不改索引名：

**第一步** — `EsDocument.java` 加字段：
```java
private String kbName;  // 知识库名称，如 "技术文档库"、"合同库"
```

**第二步** — `knowledge_base.json` mapping 加字段：
```json
"kbName": { "type": "keyword" }
```

**第三步** — 搜索时用 `kbName` 过滤：
```java
// HybridSearchService 的查询中添加 filter 条件
.filter(f -> f.term(t -> t.field("kbName").value("技术文档库")))
```

**第四步** — 上传链路传递 `kbName`：前端指定知识库 → `FileProcessingTask` 加字段 → 消费者 → `VectorizationService` → 写入 ES。

**优缺点**：
- 优点：改动小，只加字段不改索引名，搜索时可以跨知识库
- 缺点：所有知识库数据混在一起，删除某个知识库需 DeleteByQuery

### 8.3 方案二：不同知识库用不同索引（隔离性强）

**第一步** — `application.yml` 中配置多个索引：
```yaml
elasticsearch:
  indices:
    knowledge_base: "kb_tech"     # 技术文档库
    contract_base:   "kb_contract" # 合同库
```

**第二步** — `EsConfig.java` 暴露索引名 Bean：
```java
@Value("${elasticsearch.indices.knowledge_base}")
private String kbTechIndex;

@Bean("kbTechIndex")
public String kbTechIndex() { return kbTechIndex; }
```

**第三步** — 改造 `ElasticsearchService`，让索引名变为参数：
```java
// 原来写死 knowledge_base
public void bulkIndex(List<EsDocument> documents) {
    ...
    .index("knowledge_base")  // 硬编码
}

// 改造后，indexName 作为参数传入
public void bulkIndex(String indexName, List<EsDocument> documents) {
    ...
    .index(indexName)  // 动态路由
}
```

这一步需要同步修改 `VectorizationService.vectorize()` 以及 `HybridSearchService` 中所有搜索方法的索引引用。

**第四步** — `EsIndexInitializer` 中注册所有索引：
```java
// 为每个知识库创建独立索引和 mapping
List<String> indices = List.of("kb_tech", "kb_contract");
for (String indexName : indices) {
    if (!esClient.indices().exists(ExistsRequest.of(e -> e.index(indexName))).value()) {
        // 复用同一份 mapping JSON 格式，只需替换索引名
        createIndex(indexName, mappingJson);
    }
}
```

**第五步** — 上传到搜索的全链路改造：
```
前端传知识库ID → FileProcessingTask 加 kbType 字段
→ FileProcessingConsumer 传递 kbType
→ VectorizationService 根据 kbType 选择 indexName
→ HybridSearchService 根据用户请求的 kbType 选择对应 index
```

**优缺点**：
- 优点：完全隔离，互不影响；可对每个索引独立设置分片、备份策略
- 缺点：改动范围大（3-4 个文件），跨知识库搜索需搜多个索引后合并

### 8.4 两种方案对比

| 维度 | 方案一（字段区分） | 方案二（多索引） |
|------|-------------------|-----------------|
| 改动量 | 小（加字段 + filter） | 大（全链路改造） |
| 数据隔离 | 逻辑隔离 | 物理隔离 |
| 删除知识库 | DeleteByQuery（较慢） | 直接删索引（秒级） |
| 跨库搜索 | 天然支持 | 需搜多个索引后合并 |
| 运维复杂度 | 低 | 中（每个索引需单独管理） |
| 适用场景 | 知识库数量少（<10）、数据量不大 | 知识库数量多、需要独立备份/权限 |

**建议**：先用方案一，等知识库数量明显增多或多租户隔离要求变高时再迁移到方案二。
