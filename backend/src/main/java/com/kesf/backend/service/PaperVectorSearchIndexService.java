package com.kesf.backend.service;

import com.kesf.backend.service.impl.PaperVectorDocument;

import java.util.List;

/**
 * 论文向量搜索索引服务接口。
 * <p>
 * 定义对 Elasticsearch 中论文向量文档的批量写入和按 MD5 删除操作。
 * 当前由 {@code ElasticsearchPaperIndexService} 实现。
 * </p>
 */
public interface PaperVectorSearchIndexService {

    /**
     * 批量写入论文向量文档到 Elasticsearch。
     * <p>
     * 写入前应先调用 {@link #deleteByPaperMd5(String)} 清理同一论文的旧数据，
     * 以避免重复文档影响搜索结果。
     * </p>
     *
     * @param documents 待写入的向量文档列表
     */
    void bulkIndex(List<PaperVectorDocument> documents);

    /**
     * 按论文 MD5 删除 Elasticsearch 中该论文的所有向量文档。
     * <p>
     * 用于论文重新上传或删除时的旧数据清理，保证索引中无过期向量。
     * </p>
     *
     * @param paperMd5 论文 PDF 的 MD5 值
     */
    void deleteByPaperMd5(String paperMd5);
}
