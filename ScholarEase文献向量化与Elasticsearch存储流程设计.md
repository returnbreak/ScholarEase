# ScholarEase 文献向量化与 Elasticsearch 存储流程设计

## 一、整体目标

当前 ScholarEase 已完成本地 PDF 上传、MinerU 解析产物写入 MinIO、Zotero 元数据提取、`papers` / `paper_locations` 入库和文献删除功能。下一步需要把 MinIO 中已经保存的 `content_list_v2.json` 作为结构化解析源，构建论文感知的 chunk，调用 embedding 服务生成向量，并写入 Elasticsearch，支撑后续 RAG 检索。

本方案参考《PaiSmart向量化与Elasticsearch存储流程分析.md》的异步处理思路，但数据源从“原始文件解析文本”改为“MinerU 结构化 JSON”。核心链路是：

```text
PDF 上传与 MinerU 解析
  -> 原始 PDF / full.md / content_list_v2.json 写入 MinIO
  -> 在 MySQL papers 主表写入之前发送 Kafka 向量化任务
  -> Kafka Consumer 从 MinIO 读取 content_list_v2.json
  -> 解析 MinerU block，构建论文感知 chunk
  -> 批量调用 Embedding API
  -> 批量写入 Elasticsearch knowledge_base 索引
  -> MySQL 继续写入 papers / paper_locations
```

关键原则：

- Kafka 生产者放在 MySQL 主表写入之前，因此消息体不能依赖自增 `paperId`。
- 当前项目没有 MySQL 层面的 `chunkId`，第一版也不强制设计 chunk 顺序号。ES 文档 `_id` 可以直接使用随机 UUID；为避免重复消费或手动重建索引产生重复数据，Consumer 写入前先按 `paperMd5` 删除旧 ES 文档。
- Elasticsearch 中同时保存原文、向量和结构化元数据，向量不写入 MySQL。
- 删除文献时必须同步删除 ES 中同一 `paperMd5` 下的全部 chunk。

---

## 二、当前代码链路定位

当前上传入口为：

```text
DocumentController.POST /api/documents/upload
  -> DocumentServiceImpl.uploadDocument()
  -> validatePdfFile()
  -> calculateMd5()
  -> duplicate check by papers.paper_md5
  -> progressService.recordUploadProgress()
  -> parseWithMinerU()
```

`parseWithMinerU()` 中已有的核心步骤：

```text
1. uploadOriginalPdf()
   -> MinIO: uploads/{traceId}/original/{fileName}

2. parseByMinerU()
   -> MinerU signed upload
   -> poll batch result
   -> download full ZIP
   -> uploadMinerUArtifacts()
   -> MinIO: uploads/{traceId}/mineru/full.md
   -> MinIO: uploads/{traceId}/mineru/content_list_v2.json

3. zoteroImportService.importParsedPaper()
   -> 获取 title / authors / year / doi 等元数据

4. paperMapper.insert(paperEntity)
   -> 写入 papers 主表

5. paperLocationsMapper.insert(locations)
   -> 写入 MinIO / Zotero 位置信息

6. progressService.updateParseStatus(traceId, 2)
```

Kafka 生产者推荐放在第 3 步之后、第 4 步之前：

```text
MinerU 解析产物已写入 MinIO
  -> Zotero 元数据已拿到
  -> 组装 VectorIndexTask
  -> 发送 Kafka
  -> 再执行 paperMapper.insert(paperEntity)
```

这样做的好处是：Kafka 消息已经具备构建 ES 文档所需的 `paperMd5`、`traceId`、MinIO key、文件名、提交时间和论文元数据，不需要等待 MySQL 自增 ID。

---

## 三、Kafka 消息设计

### 3.1 Topic 建议

```yaml
scholarease:
  kafka:
    topics:
      paper-vector-index: paper-vector-index-topic
```

第一版只建论文向量索引 topic。论文删除不走 Kafka，由删除业务服务直接调用 ES `deleteByPaperMd5()`，避免删除动作异步化后出现“页面已删除但检索仍命中”的短暂不一致。

### 3.2 向量化任务消息体

```json
{
  "taskId": "traceId:paperMd5",
  "traceId": "9e37d2f928b44f87",
  "paperMd5": "1f3870be274f6c49b3e31a0c6728957f",
  "fileName": "attention.pdf",
  "fileSizeBytes": 2148123,
  "submissionTime": "2026-05-12T10:30:00.123+08:00",
  "minioBucket": "literatures",
  "contentListObjectKey": "uploads/9e37d2f928b44f87/mineru/content_list_v2.json",
  "fullMarkdownObjectKey": "uploads/9e37d2f928b44f87/mineru/full.md",
  "title": "Attention Is All You Need",
  "authors": ["Ashish Vaswani", "Noam Shazeer"],
  "keywords": ["transformer", "attention"],
  "language": "en",
  "year": 2017,
  "venue": "NeurIPS",
  "doi": "10.5555/3295222.3295349",
  "modelVersion": "BAAI/bge-m3"
}
```

字段说明：

| 字段 | 来源 | 用途 |
|---|---|---|
| `taskId` | 后端生成 | Kafka 幂等与日志追踪 |
| `traceId` | 上传请求 | 定位 MinIO 路径、排查链路 |
| `paperMd5` | 后端复核 MD5 | ES 删除、去重、重试或重建索引前的清理条件 |
| `contentListObjectKey` | 固定路径规则 | Consumer 读取 MinerU JSON |
| `title/authors/year/doi` | Zotero 元数据 | ES metadata filter 与 chunk 前缀 |
| `modelVersion` | embedding 配置 | 向量版本追踪 |

注意：因为生产者发生在 MySQL 写入前，消息里不放 `paperId`。后续如果需要在 ES 中补 `paperId`，可以增加一个“索引完成后回填 / 重新索引”机制，但第一版没有必要。

---

## 四、Consumer 处理流程

Kafka Consumer 监听 `paper-vector-index-topic` 后执行：

```text
1. 接收 VectorIndexTask
2. 根据 contentListObjectKey 从 MinIO 下载 content_list_v2.json
3. 解析 JSON 为 page -> block 结构
4. 过滤 page_header / page_footer / page_number 等噪声
5. 按 title 维护 sectionPath
6. 将 paragraph / equation / table / image caption 转成可检索文本
7. 构建 section-aware chunks
8. 批量调用 EmbeddingClient.embed(texts)
9. 组装 EsPaperChunkDocument
10. Elasticsearch Bulk API 写入 knowledge_base
```

失败处理：

- MinIO 读取失败：让 Kafka 重试，超过次数进入 DLT。
- JSON 结构异常：记录 `traceId`、`paperMd5`、object key，进入 DLT。
- Embedding API 部分失败：整批重试；避免同一论文只写入一半。
- ES bulk 部分失败：抛异常触发重试；Consumer 重试写入前先按 `paperMd5` 清理旧 ES 文档，再重新 bulk 写入，避免随机 UUID 造成重复数据。

---

## 五、content_list_v2.json 解析策略

示例文件 `mineru/9bbfd84b-06a0-475b-a87e-68cf7148f26a_content_list_v2.json` 的顶层结构是二维数组：

```text
[
  [ page0_block0, page0_block1, ... ],
  [ page1_block0, page1_block1, ... ]
]
```

每个 block 通常包含：

| 字段 | 说明 |
|---|---|
| `type` | block 类型，如 `title`、`paragraph`、`image`、`table`、`equation_interline` |
| `content` | 不同类型的结构化内容 |
| `bbox` | block 在页面中的坐标 |

### 5.1 类型处理规则

| MinerU 类型 | 是否入 ES | 处理方式 |
|---|---:|---|
| `title` | 是 | 提取标题文本，更新当前 `sectionPath`，也可作为 chunk |
| `paragraph` | 是 | 提取 `paragraph_content` 中的 text / equation_inline，拼成正文 |
| `equation_interline` | 是 | 提取公式图片或公式文本，拼接前后段落上下文 |
| `table` | 是 | 提取 `table_caption` + `html`，单独形成 table chunk |
| `image` | 可选 | 第一版只索引 caption / footnote；图片 OCR 或图像理解后续补充 |
| `page_header` | 否 | 过滤 |
| `page_footer` | 否 | 过滤 |
| `page_number` | 否 | 过滤 |
| `page_footnote` | 可选 | 可作为当前页脚注附加到邻近 chunk |

### 5.2 文本抽取规则

正文类 block 的抽取应保留 inline equation：

```text
paragraph_content:
  text -> 原样拼接
  equation_inline -> 用 `$...$` 或 `[公式]` 包裹后拼接
```

表格类 block：

```text
textContent =
  "Table: " + table_caption
  + "\nSection: " + sectionPath
  + "\nHTML:\n" + html
```

图片类 block：

```text
textContent =
  "Figure: " + image_caption
  + "\nImage Path: " + image_source.path
  + "\nFootnote: " + image_footnote
```

当前 `uploadMinerUArtifacts()` 只上传 `full.md` 和 `content_list_v2.json`，没有上传 `images/` 目录。第一版可先索引图片 caption 和相对路径；如果后续要让图像可预览或做多模态检索，需要把 ZIP 中的 images 同步上传到 MinIO。

---

## 六、Chunk 构建格式

第一版建议按“论文感知 + 结构保真”方式构建 chunk，而不是重新用固定长度粗暴切 PDF 文本。

### 6.1 chunk 文本格式

每个写入 embedding 的文本应带上下文前缀：

```text
Paper: {title}
Authors: {authorText}
Year: {year}
Venue: {venue}
DOI: {doi}
Section: {sectionPath}
Page: {pageNo}
Type: {chunkType}

{block text or merged chunk text}
```

这样向量召回时不会丢掉论文标题、章节路径和页码信息。

### 6.2 chunk 粒度建议

| 类型 | 粒度 |
|---|---|
| `title + abstract` | 单独 paper-level chunk |
| 普通段落 | 按 section 聚合，目标 500-900 tokens |
| 方法 / 实验 / 结果段落 | 目标 400-700 tokens，避免拆断指标和数据集 |
| 表格 | 一表一 chunk，保留 caption 和 HTML |
| 图片 | 一图一 chunk，第一版只用 caption |
| 公式 | 公式与相邻段落合并，保留公式上下文 |

### 6.3 chunk 元数据

建议 Consumer 内部构建 `PaperChunk`：

| 字段 | 说明 |
|---|---|
| `chunkIndex` | 可选调试字段。Consumer 构建 chunk 时可生成顺序号，但不要求写入 MySQL，也不作为 ES 主键 |
| `paperMd5` | 文件唯一标识 |
| `traceId` | 上传链路 |
| `chunkType` | `title` / `paragraph` / `table` / `figure` / `equation` |
| `sectionTitle` | 当前章节标题 |
| `sectionPath` | 多级章节路径 |
| `pageStart` / `pageEnd` | 页码，从 `content_list_v2` 页数组下标推导 |
| `bbox` | 当前 block 或合并 block 的坐标 |
| `rawText` | 原始抽取文本 |
| `contextText` | 加了论文元数据前缀后的 embedding 文本 |
| `contentHash` | `paperMd5 + rawText` 的 hash，用于去重 |
| `sourceObjectKey` | `content_list_v2.json` 的 MinIO key |

---

## 七、Elasticsearch 文档设计

参考 PaiSmart 的 `EsDocument`，ScholarEase 建议使用增强版文档结构：

```json
{
  "id": "7b8fd15d-9d1c-41e5-bc5f-97a2a7c3b4f0",
  "paperMd5": "1f3870be274f6c49b3e31a0c6728957f",
  "traceId": "9e37d2f928b44f87",
  "chunkIndex": 1,
  "chunkType": "paragraph",
  "textContent": "Paper: ...\nSection: ...\n\n正文内容",
  "rawText": "正文内容",
  "vector": [0.0123, -0.0456],
  "modelVersion": "BAAI/bge-m3",
  "title": "Attention Is All You Need",
  "authors": ["Ashish Vaswani", "Noam Shazeer"],
  "authorText": "Ashish Vaswani, Noam Shazeer",
  "keywords": ["transformer", "attention"],
  "language": "en",
  "year": 2017,
  "venue": "NeurIPS",
  "doi": "10.5555/3295222.3295349",
  "fileName": "attention.pdf",
  "sectionTitle": "3. Method",
  "sectionPath": "3. Method / 3.1 Architecture",
  "pageStart": 4,
  "pageEnd": 4,
  "bbox": [156, 227, 829, 251],
  "sourceObjectKey": "uploads/9e37d2f928b44f87/mineru/content_list_v2.json",
  "createdAt": "2026-05-17T10:30:00+08:00"
}
```

### 7.1 推荐 mapping

| 字段 | ES 类型 | 用途 |
|---|---|---|
| `paperMd5` | `keyword` | 按文献过滤和删除 |
| `traceId` | `keyword` | 上传链路追踪 |
| `chunkIndex` | `integer` | 可选调试字段，用于查看同一论文内 chunk 大致顺序 |
| `chunkType` | `keyword` | 类型过滤 |
| `textContent` | `text` | BM25 / 全文检索 |
| `rawText` | `text` | 调试和展示 |
| `vector` | `dense_vector` | 向量检索，维度与 embedding 配置一致 |
| `modelVersion` | `keyword` | 向量模型版本 |
| `title` | `text + keyword` | 检索和过滤 |
| `authors` | `keyword` | 作者过滤 |
| `authorText` | `text` | 作者全文匹配 |
| `keywords` | `keyword` | 关键词过滤 |
| `language` | `keyword` | 语言过滤 |
| `year` | `integer` | 年份过滤 |
| `venue` | `text + keyword` | 期刊/会议过滤 |
| `doi` | `keyword` | DOI 精确匹配 |
| `sectionPath` | `text + keyword` | 章节检索和过滤 |
| `pageStart/pageEnd` | `integer` | provenance |
| `bbox` | `integer` 数组 | 页面坐标 |
| `sourceObjectKey` | `keyword` | 回溯 MinIO 解析产物 |

索引名第一版默认使用 `scholarease_bge_m3`，明确标识当前索引使用 `BAAI/bge-m3` 的 1024 维向量。如果本地已有旧的 `scholarease_base` 2048 维索引，不要混用；应保留旧索引并切到新索引名，或删除旧索引后重建。

---

## 八、Embedding 与批量写入

### 8.1 EmbeddingClient

当前项目还没有 embedding 配置和客户端。可参考 PaiSmart：

```yaml
embedding:
  api:
    url: https://api.deepinfra.com/v1/openai
    key: ${DEEPINFRA_API_TOKEN:${EMBEDDING_API_KEY:}}
    model: BAAI/bge-m3
    batch-size: 10
    dimension: 1024
```

Consumer 将 `PaperChunk.contextText` 批量传入 embedding 服务：

```text
chunks -> texts -> embeddingClient.embed(texts) -> List<float[]>
```

### 8.2 ES bulk 写入

ES `_id` 第一版建议直接使用随机 UUID：

```text
UUID.randomUUID().toString()
```

例如：

```text
7b8fd15d-9d1c-41e5-bc5f-97a2a7c3b4f0
```

这样实现最简单，也不需要为当前项目额外设计 chunk 编号体系。代价是：同一篇论文如果 Kafka 重试、手动补偿或重建索引，随机 UUID 会生成新文档，不能天然覆盖旧文档。

因此批量写入前必须先执行：

```text
deleteByPaperMd5(paperMd5)
```

再 bulk index。这样同一篇论文重试或重建索引时，会先清空旧 chunk，再写入新 chunk，不会残留重复数据。

这里的“重建索引”指的是：删除 ES 中某篇论文的旧向量/文本数据，然后重新读取 MinIO 中的 `content_list_v2.json`，重新构建 chunk、重新生成 embedding、重新写入 ES。典型场景包括：

- 第一次 ES 写入失败或只写入一半，需要重跑。
- 后续调整 chunk 构建规则，例如把 table、image caption、equation 加入索引。
- 更换 embedding 模型或向量维度。
- 修改 ES mapping 或索引名。
- Kafka 消费失败进入 DLT，修复后重新消费。

`chunkIndex` 在第一版只作为可选调试字段：需要排查“第几段内容写入 ES”时可以保留；如果后续用处不大，也可以完全不存。

---

## 九、删除链路补充

当前 `deleteDocument(paperId)` 已删除：

```text
MinIO 原始 PDF
MinIO 解析产物目录
Zotero item
paper_locations
papers
```

接入 ES 后需要补充：

```text
根据 paper.paperMd5 删除 ES 中所有 chunk
```

推荐删除顺序：

```text
DELETE /api/documents/{paperId}
  -> 查询 papers，拿到 paperMd5
  -> 删除 ES: deleteByPaperMd5(paperMd5)
  -> 删除 MinIO 原始 PDF 和解析产物
  -> 删除 Zotero item
  -> 删除 paper_locations
  -> 删除 papers
```

论文删除功能不使用 Kafka。第一版建议删除 MySQL 前同步删除 ES：如果 ES 删除失败，则阻断删除并返回错误，避免用户删除后仍可能在问答检索里命中该论文。

---

## 十、需要新增或调整的模块

### 10.1 Maven 依赖

当前 `backend/pom.xml` 还没有 Kafka starter，需要新增：

```text
spring-kafka
```

Elasticsearch starter 已存在，但仍需确认使用的是 Spring Data Elasticsearch 还是 ES Java Client。若采用 ES Java Client，需要补充显式客户端配置。

### 10.2 配置类

| 类 | 职责 |
|---|---|
| `KafkaConfig` | Producer / Consumer / DLT / JSON 序列化配置 |
| `EmbeddingProperties` | 绑定 embedding API 参数 |
| `ElasticsearchIndexProperties` | 绑定索引名、向量维度 |

### 10.3 Kafka DTO

| 类 | 职责 |
|---|---|
| `PaperVectorIndexTask` | 上传链路发送到 Kafka 的索引任务 |

### 10.4 解析与分块服务

| 类 | 职责 |
|---|---|
| `MinerUContentListReader` | 从 MinIO 读取并反序列化 `content_list_v2.json` |
| `MinerUBlockTextExtractor` | 从 title / paragraph / table / image / equation 中抽文本 |
| `PaperChunkBuildService` | 维护 sectionPath，合并 block，生成 chunk |

### 10.5 向量与 ES 服务

| 类 | 职责 |
|---|---|
| `EmbeddingClient` | 批量调用 embedding API |
| `PaperVectorizationService` | 编排 chunk -> embedding -> ES document |
| `ElasticsearchPaperIndexService` | 创建索引、bulk 写入、按 `paperMd5` 删除 |
| `PaperVectorIndexConsumer` | Kafka Consumer 入口 |

---

## 十一、实现步骤建议

### Step 1：补齐 Kafka 基础设施

新增 Kafka 依赖和配置，定义 `paper-vector-index-topic`、消费者组、重试和 DLT。

Producer 使用 JSON 序列化；Consumer 反序列化为 `PaperVectorIndexTask`。

### Step 2：在 MySQL 写入前发送 Kafka

在 `DocumentServiceImpl.parseWithMinerU()` 中，放置点为：

```text
zoteroImportService.importParsedPaper()
  -> toPaperEntity(...)
  -> build PaperVectorIndexTask
  -> kafkaTemplate.send(...)
  -> paperMapper.insert(paperEntity)
```

消息体必须带齐 MinIO object key 和论文元数据，不能依赖 `paperId`。

### Step 3：增加 ObjectStorageService 读取能力

当前 `ObjectStorageService` 只有上传、删除、按 prefix 列举能力。Consumer 需要从 MinIO 下载 `content_list_v2.json`，因此需要新增：

```text
getObjectBytes(objectKey)
```

或：

```text
getObjectStream(objectKey)
```

### Step 4：实现 MinerU JSON 解析

按页遍历二维数组：

```text
for pageIndex in pages:
  for block in page:
    read type/content/bbox
```

过滤页眉页脚，维护章节路径，抽取正文、表格、图片 caption 和公式文本。

### Step 5：构建 chunk

将相邻的 paragraph block 在同一 section 内合并；遇到 table / image / equation 时单独成 chunk 或与邻近段落合并。

每个 chunk 加论文元数据前缀，并保存页码、bbox、sectionPath。

### Step 6：Embedding 批处理

按配置 batch-size 批量调用 embedding API。确保返回向量数量和 chunk 数量一致；不一致则整篇失败重试。

### Step 7：写入 Elasticsearch

启动时创建索引 mapping。Consumer 写入前先按 `paperMd5` 删除旧文档，再 bulk index 新文档。

### Step 8：补齐删除同步

在 `deleteDocument()` 删除 MySQL 前调用：

```text
elasticsearchPaperIndexService.deleteByPaperMd5(paper.getPaperMd5())
```

必要时再增加删除 topic 做补偿。

### Step 9：补齐测试

至少覆盖：

- `content_list_v2.json` 中 title / paragraph / table / image / equation 的抽取。
- 随机 UUID 写入前会先 `deleteByPaperMd5(paperMd5)`，重试或重建索引不会产生重复 ES 文档。
- 相同 `paperMd5` 重复消费不会产生重复 ES 文档。
- 删除文献时 ES、MinIO、Zotero、MySQL 数据一致。
- Embedding 返回数量不一致时会失败重试。

---

## 十二、完整流程图

```text
┌──────────────────────────────────────────────────────────────┐
│ 1. 用户上传 PDF                                               │
│ POST /api/documents/upload                                    │
└───────────────────────────────┬──────────────────────────────┘
                                ▼
┌──────────────────────────────────────────────────────────────┐
│ 2. 后端校验与查重                                             │
│ 校验 PDF / size / MD5，按 papers.paper_md5 查重                │
└───────────────────────────────┬──────────────────────────────┘
                                ▼
┌──────────────────────────────────────────────────────────────┐
│ 3. MinerU 解析并写入 MinIO                                    │
│ original PDF、full.md、content_list_v2.json                   │
└───────────────────────────────┬──────────────────────────────┘
                                ▼
┌──────────────────────────────────────────────────────────────┐
│ 4. Zotero 提取论文元数据                                      │
│ title / authors / year / venue / doi                          │
└───────────────────────────────┬──────────────────────────────┘
                                ▼
┌──────────────────────────────────────────────────────────────┐
│ 5. MySQL 写入前发送 Kafka                                     │
│ PaperVectorIndexTask(traceId, paperMd5, contentListObjectKey) │
└───────────────┬──────────────────────────────┬───────────────┘
                ▼                              ▼
┌──────────────────────────────┐ ┌──────────────────────────────┐
│ 6A. Consumer 异步向量化       │ │ 6B. 主链路继续 MySQL 入库      │
│ 读取 content_list_v2.json     │ │ paperMapper.insert            │
│ 构建 chunk                    │ │ paperLocationsMapper.insert   │
│ Embedding                     │ │ update parse_status=2         │
│ Bulk 写入 ES                  │ │                              │
└──────────────────────────────┘ └──────────────────────────────┘
```

---

## 十三、第一版落地优先级

1. Kafka 依赖、配置、`PaperVectorIndexTask`。
2. 在 `paperMapper.insert()` 之前发送 Kafka。
3. `ObjectStorageService` 增加 MinIO 读取方法。
4. `content_list_v2.json` 解析与 chunk 构建。
5. Embedding API 客户端。
6. ES mapping、bulk index、`deleteByPaperMd5()`。
7. 删除文献时同步删除 ES。
8. 针对示例 `mineru/*_content_list_v2.json` 写单元测试。

---

## 十四、最终建议

第一版不要再走 PaiSmart 中 “Apache Tika 解析 PDF -> MySQL document_vectors -> 再向量化” 的路径。ScholarEase 已经有 MinerU 的结构化结果，应该直接以 MinIO 中的 `content_list_v2.json` 为唯一解析源，构建带章节、页码、bbox、表格和图片 caption 的结构化 chunk。

也就是说，推荐的工程路线是：

```text
MinerU content_list_v2.json
  -> section-aware chunk
  -> embedding
  -> Elasticsearch dense_vector + BM25
  -> delete by paperMd5
```

这样既符合研究论文知识库的结构化 RAG 策略，也能保持和当前上传、删除、MinIO 存储链路的最小改动。
