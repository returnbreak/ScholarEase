package com.kesf.backend.service.impl;

import com.kesf.backend.kafka.PaperVectorIndexTask;
import com.kesf.backend.service.EmbeddingClient;
import com.kesf.backend.service.ObjectStorageService;
import com.kesf.backend.service.PaperVectorSearchIndexService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

/**
 * 论文向量化服务——Kafka 消费者的核心处理逻辑。
 * <p>
 * 接收 {@link PaperVectorIndexTask}，执行完整的向量化流水线：
 * </p>
 * <ol>
 *   <li>从 MinIO 读取 MinerU 解析产物的 content_list_v2.json</li>
 *   <li>调用 {@link PaperChunkBuildService} 将 JSON 拆分为内容块列表</li>
 *   <li>调用 {@link EmbeddingClient} 将每个块的上下文文本向量化</li>
 *   <li>先删除 ES 中该论文的旧数据，再批量写入新向量文档</li>
 * </ol>
 * <p>
 * 核心不变量：{@link #index(PaperVectorIndexTask)} 方法内的向量数量必须与 chunk 数量一致，
 * 否则视为数据完整性问题，拒绝写入并抛出异常。
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaperVectorizationService {

    private final ObjectStorageService objectStorageService;
    private final PaperChunkBuildService paperChunkBuildService;
    private final EmbeddingClient embeddingClient;
    private final PaperVectorSearchIndexService searchIndexService;

    /**
     * 执行论文向量化并写入 Elasticsearch。
     * <p>
     * 整体流程：MinIO 读取 → 分块 → Embedding → 删旧 → 写新。
     * 若 contentListObjectKey 为空或提取到的 chunk 数为 0，则提前返回（不写 ES）。
     * </p>
     *
     * @param task 论文向量索引任务
     * @throws IllegalArgumentException 若 task 或 contentListObjectKey 为空
     * @throws IllegalStateException    若 Embedding 返回的向量数与 chunk 数不匹配
     */
    public void index(PaperVectorIndexTask task) {
        if (task == null || !StringUtils.hasText(task.getContentListObjectKey())) {
            throw new IllegalArgumentException("Paper vector index task missing contentListObjectKey");
        }

        log.info("Start paper vectorization: taskId={}, traceId={}, paperMd5={}, contentListObjectKey={}",
                task.getTaskId(), task.getTraceId(), task.getPaperMd5(), task.getContentListObjectKey());

        // 1. 从 MinIO 读取 MinerU 解析产物的 content_list_v2.json
        byte[] contentListBytes = objectStorageService.getObjectBytes(task.getContentListObjectKey());

        // 2. 将 JSON 解析并拆分为内容块
        List<PaperChunk> chunks = paperChunkBuildService.buildChunks(task, contentListBytes);
        if (chunks.isEmpty()) {
            throw new IllegalStateException("No searchable MinerU chunks built for paperMd5=" + task.getPaperMd5());
        }

        // 3. 批量向量化所有 chunk 的上下文增强文本
        List<String> texts = chunks.stream()
                .map(PaperChunk::contextText)
                .toList();
        List<float[]> vectors = embeddingClient.embed(texts);

        // 核心不变量检查：向量数必须与 chunk 数一致
        if (vectors.size() != chunks.size()) {
            throw new IllegalStateException("Embedding vector count " + vectors.size()
                    + " does not match chunk count " + chunks.size());
        }

        // 4. 组装 ES 文档
        List<PaperVectorDocument> documents = IntStream.range(0, chunks.size())
                .mapToObj(index -> toDocument(task, chunks.get(index), vectors.get(index)))
                .toList();

        // 5. 先删旧（覆盖写入语义），再批量写新
        searchIndexService.deleteByPaperMd5(task.getPaperMd5());
        searchIndexService.bulkIndex(documents);
        log.info("Paper vectorization finished: taskId={}, paperMd5={}, chunks={}",
                task.getTaskId(), task.getPaperMd5(), documents.size());
    }

    /**
     * 将 Kafka 任务 + 内容块 + 向量组装为 ES 文档对象。
     * <p>
     * 文档 ID 使用随机 UUID，保证每次重索引都生成全新的文档（配合 deleteByPaperMd5 实现覆盖写入）。
     * </p>
     */
    private PaperVectorDocument toDocument(PaperVectorIndexTask task, PaperChunk chunk, float[] vector) {
        PaperVectorDocument document = new PaperVectorDocument();
        document.setId(UUID.randomUUID().toString());
        // 关联信息
        document.setPaperMd5(task.getPaperMd5());
        document.setTraceId(task.getTraceId());
        // 内容块信息
        document.setChunkIndex(chunk.chunkIndex());
        document.setChunkType(chunk.chunkType());
        document.setTextContent(chunk.contextText()); // 上下文增强文本（用于搜索匹配）
        document.setRawText(chunk.rawText());         // 原始文本（用于展示）
        document.setVector(vector);
        document.setModelVersion(task.getModelVersion());
        // 论文元数据
        document.setTitle(task.getTitle());
        document.setAuthors(task.getAuthors() == null ? List.of() : task.getAuthors());
        document.setAuthorText(String.join(", ",
                task.getAuthors() == null ? List.of() : task.getAuthors()));
        document.setKeywords(task.getKeywords() == null ? List.of() : task.getKeywords());
        document.setLanguage(task.getLanguage());
        document.setYear(task.getYear());
        document.setVenue(task.getVenue());
        document.setDoi(task.getDoi());
        document.setFileName(task.getFileName());
        // 位置信息
        document.setSectionTitle(chunk.sectionTitle());
        document.setSectionPath(chunk.sectionPath());
        document.setPageStart(chunk.pageStart());
        document.setPageEnd(chunk.pageEnd());
        document.setBbox(chunk.bbox());
        // 来源追溯
        document.setSourceObjectKey(chunk.sourceObjectKey());
        document.setContentHash(chunk.contentHash());
        document.setCreatedAt(OffsetDateTime.now().toString());
        return document;
    }
}
