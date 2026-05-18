package com.kesf.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kesf.backend.kafka.PaperVectorIndexTask;
import com.kesf.backend.service.impl.MinerUBlockTextExtractor;
import com.kesf.backend.service.impl.PaperChunkBuildService;
import com.kesf.backend.service.impl.PaperVectorDocument;
import com.kesf.backend.service.impl.PaperVectorizationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link PaperVectorizationService} 的单元测试。
 * <p>
 * 验证完整的向量化流水线：MinIO 读取 → 分块 → Embedding → ES 删旧 → ES 写新。
 * 同时验证向量数与 chunk 数不匹配时的安全拒绝机制。
 * </p>
 */
@ExtendWith(MockitoExtension.class)
class PaperVectorizationServiceTests {

    @Mock
    private ObjectStorageService objectStorageService;

    @Mock
    private EmbeddingClient embeddingClient;

    @Mock
    private PaperVectorSearchIndexService searchIndexService;

    /**
     * 验证正常的向量化流程：
     * <ol>
     *   <li>从 MinIO 读取 content_list_v2.json</li>
     *   <li>调用 Embedding 客户端向量化</li>
     *   <li>先删除旧文档，再批量写入新文档（有序执行）</li>
     *   <li>写入的文档包含完整的元数据、向量和来源信息</li>
     * </ol>
     */
    @Test
    void indexReadsMinerUJsonEmbedsChunksAndBulkWritesElasticsearchDocuments() {
        PaperVectorizationService service = service();
        PaperVectorIndexTask task = task();
        when(objectStorageService.getObjectBytes("uploads/trace-001/mineru/content_list_v2.json"))
                .thenReturn(contentList());
        // 模拟 Embedding 返回 2 个向量（对应 2 个 chunk）
        when(embeddingClient.embed(anyList()))
                .thenReturn(List.of(new float[]{0.1f, 0.2f}, new float[]{0.3f, 0.4f}));

        service.index(task);

        // 验证调用顺序：读取 → Embedding → 删旧 → 写新
        var inOrder = inOrder(objectStorageService, embeddingClient, searchIndexService);
        inOrder.verify(objectStorageService).getObjectBytes("uploads/trace-001/mineru/content_list_v2.json");
        inOrder.verify(embeddingClient).embed(anyList());
        inOrder.verify(searchIndexService).deleteByPaperMd5("md5-001"); // 先删旧
        ArgumentCaptor<List<PaperVectorDocument>> documentsCaptor = ArgumentCaptor.forClass(List.class);
        inOrder.verify(searchIndexService).bulkIndex(documentsCaptor.capture()); // 再写新

        // 验证写入的文档内容
        List<PaperVectorDocument> documents = documentsCaptor.getValue();
        assertThat(documents).hasSize(2);
        assertThat(documents.get(0).getId()).isNotBlank(); // UUID 非空
        assertThat(documents.get(0).getPaperMd5()).isEqualTo("md5-001");
        assertThat(documents.get(0).getTraceId()).isEqualTo("trace-001");
        assertThat(documents.get(0).getChunkType()).isEqualTo("title");
        assertThat(documents.get(0).getTextContent()).contains("Paper: Attention Is All You Need");
        assertThat(documents.get(0).getVector()).containsExactly(0.1f, 0.2f);
        assertThat(documents.get(0).getModelVersion()).isEqualTo("BAAI/bge-m3");
        assertThat(documents.get(0).getSourceObjectKey())
                .isEqualTo("uploads/trace-001/mineru/content_list_v2.json");
        assertThat(documents.get(1).getRawText()).contains("Self-attention connects all positions.");
        assertThat(documents.get(1).getAuthors()).containsExactly("Ashish Vaswani", "Noam Shazeer");
    }

    /**
     * 验证当 Embedding 返回的向量数与 chunk 数不一致时：
     * <ul>
     *   <li>抛出 IllegalStateException（数据完整性保护）</li>
     *   <li>不执行任何 ES 写入或删除操作</li>
     * </ul>
     */
    @Test
    void indexFailsWithoutWritingWhenEmbeddingCountDoesNotMatchChunkCount() {
        PaperVectorizationService service = service();
        PaperVectorIndexTask task = task();
        when(objectStorageService.getObjectBytes("uploads/trace-001/mineru/content_list_v2.json"))
                .thenReturn(contentList());
        // 只返回 1 个向量，但 chunk 有 2 个 → 不匹配
        when(embeddingClient.embed(anyList()))
                .thenReturn(List.of(new float[]{0.1f, 0.2f}));

        assertThatThrownBy(() -> service.index(task))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Embedding vector count");

        // 验证：数量不匹配时不会执行任何 ES 操作
        verify(searchIndexService, never()).deleteByPaperMd5("md5-001");
        verify(searchIndexService, never()).bulkIndex(anyList());
    }

    @Test
    void indexFailsWhenMinerUJsonBuildsNoSearchableChunks() {
        PaperVectorizationService service = service();
        PaperVectorIndexTask task = task();
        when(objectStorageService.getObjectBytes("uploads/trace-001/mineru/content_list_v2.json"))
                .thenReturn("[]".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> service.index(task))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No searchable MinerU chunks");

        verify(embeddingClient, never()).embed(anyList());
        verify(searchIndexService, never()).deleteByPaperMd5("md5-001");
        verify(searchIndexService, never()).bulkIndex(anyList());
    }

    /** 构造被测服务实例，ChunkBuildService 使用真实实现 */
    private PaperVectorizationService service() {
        return new PaperVectorizationService(
                objectStorageService,
                new PaperChunkBuildService(new ObjectMapper(), new MinerUBlockTextExtractor()),
                embeddingClient,
                searchIndexService
        );
    }

    /** 构造测试用的 PaperVectorIndexTask */
    private static PaperVectorIndexTask task() {
        PaperVectorIndexTask task = new PaperVectorIndexTask();
        task.setTraceId("trace-001");
        task.setPaperMd5("md5-001");
        task.setFileName("attention.pdf");
        task.setContentListObjectKey("uploads/trace-001/mineru/content_list_v2.json");
        task.setTitle("Attention Is All You Need");
        task.setAuthors(List.of("Ashish Vaswani", "Noam Shazeer"));
        task.setKeywords(List.of("transformer", "attention"));
        task.setLanguage("en");
        task.setYear(2017);
        task.setVenue("NeurIPS");
        task.setDoi("10.5555/3295222.3295349");
        task.setModelVersion("BAAI/bge-m3");
        return task;
    }

    /**
     * 构造测试用的 MinerU content_list_v2.json 内容。
     * 包含 2 个块：一个标题和一个段落。
     */
    private static byte[] contentList() {
        return """
                [
                  [
                    {"type":"title","content":{"level":1,"title_content":[{"type":"text","content":"Abstract"}]}},
                    {"type":"paragraph","content":{"paragraph_content":[{"type":"text","content":"Self-attention connects all positions."}]}}
                  ]
                ]
                """.getBytes(StandardCharsets.UTF_8);
    }
}
