package com.kesf.backend.service.impl;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.DeleteByQueryRequest;
import co.elastic.clients.elasticsearch.core.bulk.BulkOperation;
import co.elastic.clients.elasticsearch.core.bulk.BulkResponseItem;
import co.elastic.clients.elasticsearch.indices.get_mapping.IndexMappingRecord;
import co.elastic.clients.elasticsearch._types.mapping.Property;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import com.kesf.backend.config.EmbeddingProperties;
import com.kesf.backend.config.ScholarEaseElasticsearchProperties;
import com.kesf.backend.service.PaperVectorSearchIndexService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 基于 Elasticsearch 的论文向量搜索索引服务。
 * <p>
 * 实现论文向量文档的批量写入和按 MD5 删除。首次写入时自动检查并创建索引，
 * 索引映射定义从 classpath 中的 {@code es-mappings/scholarease_base.json} 读取。
 * </p>
 * <p>
 * 关键设计点：
 * </p>
 * <ul>
 *   <li>双重检查锁定（DCL）保证索引创建只执行一次</li>
 *   <li>批量写入使用 {@code BulkRequest} 减少网络往返</li>
 *   <li>错误检查：bulk 操作后逐条检查是否有失败项</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ElasticsearchPaperIndexService implements PaperVectorSearchIndexService {

    private final ElasticsearchClient elasticsearchClient;
    private final ScholarEaseElasticsearchProperties properties;
    private final EmbeddingProperties embeddingProperties;

    /** classpath 中的索引映射定义文件（JSON 格式），包含字段类型、分词器、向量维度等配置 */
    @Value("classpath:es-mappings/scholarease_base.json")
    private Resource mappingResource;

    /** 索引是否已检查过（DCL 标志），volatile 保证多线程可见性 */
    private volatile boolean indexChecked;

    /**
     * 批量写入论文向量文档到 Elasticsearch。
     * <p>
     * 若文档列表为空则直接返回。写入前先自动确保索引存在。
     * 遇到部分写入失败时，逐条记录错误原因并抛出异常。
     * </p>
     *
     * @param documents 待写入的向量文档列表
     */
    @Override
    public void bulkIndex(List<PaperVectorDocument> documents) {
        if (documents == null || documents.isEmpty()) {
            return;
        }
        try {
            ensureIndexExists(); // 懒初始化：首次写入时自动创建 ES 索引
            List<BulkOperation> operations = documents.stream()
                    .map(document -> BulkOperation.of(operation -> operation.index(index -> index
                            .index(properties.getIndexName()) // 目标索引名
                            .id(document.getId())             // 文档 _id
                            .document(document)                // 文档内容（自动 JSON 序列化）
                    )))
                    .toList();
            BulkResponse response = elasticsearchClient.bulk(
                    BulkRequest.of(request -> request.operations(operations)));
            if (response.errors()) {
                // 批量操作中的部分错误：逐条记录并抛出异常
                for (BulkResponseItem item : response.items()) {
                    if (item.error() != null) {
                        log.error("Elasticsearch bulk index failed: id={}, reason={}",
                                item.id(), item.error().reason());
                    }
                }
                throw new IllegalStateException("Elasticsearch bulk index partially failed");
            }
            log.info("Elasticsearch bulk index succeeded: index={}, documents={}",
                    properties.getIndexName(), documents.size());
        } catch (Exception exception) {
            throw new IllegalStateException("Elasticsearch bulk index failed: " + exception.getMessage(), exception);
        }
    }

    /**
     * 按论文 MD5 删除 Elasticsearch 中该论文的所有向量文档。
     * <p>
     * 使用 {@code delete_by_query} + {@code term} 查询，效率高于逐条 delete。
     * </p>
     *
     * @param paperMd5 论文 PDF 的 MD5 值
     */
    @Override
    public void deleteByPaperMd5(String paperMd5) {
        try {
            DeleteByQueryRequest request = DeleteByQueryRequest.of(delete -> delete
                    .index(properties.getIndexName())
                    .query(query -> query.term(term -> term
                            .field("paperMd5")  // 按 paperMd5 字段精确匹配
                            .value(paperMd5)))
            );
            elasticsearchClient.deleteByQuery(request);
        } catch (ElasticsearchException exception) {
            // 索引不存在时无需删除，静默成功（首次上传时索引尚未创建）
            if (exception.getMessage() != null && exception.getMessage().contains("index_not_found_exception")) {
                log.debug("Index {} does not exist, skip delete: paperMd5={}",
                        properties.getIndexName(), paperMd5);
                return;
            }
            throw new IllegalStateException(
                    "Elasticsearch delete by paperMd5 failed: " + exception.getMessage(), exception);
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "Elasticsearch delete by paperMd5 failed: " + exception.getMessage(), exception);
        }
    }

    /**
     * 确保索引存在（双重检查锁定）。
     * <p>
     * 首次调用时检查索引是否已存在——若不存在，从 classpath 读取映射定义文件并创建。
     * 使用 volatile + synchronized 双重检查锁定，保证线程安全且仅初始化一次。
     * </p>
     */
    private void ensureIndexExists() throws Exception {
        if (indexChecked) {
            return;
        }
        synchronized (this) {
            if (indexChecked) {
                return;
            }
            boolean exists = elasticsearchClient.indices()
                    .exists(existsRequest -> existsRequest.index(properties.getIndexName()))
                    .value();
            if (!exists) {
                // 从 classpath 读取预定义的索引映射（字段类型、向量维度、分词器等）
                String mappingJson = new String(
                        mappingResource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                elasticsearchClient.indices().create(create -> create
                        .index(properties.getIndexName())
                        .withJson(new StringReader(mappingJson)));
                log.info("Elasticsearch index created: {}", properties.getIndexName());
            } else {
                validateExistingVectorMapping();
            }
            indexChecked = true;
        }
    }

    private void validateExistingVectorMapping() throws Exception {
        IndexMappingRecord mappingRecord = elasticsearchClient.indices()
                .getMapping(request -> request.index(properties.getIndexName()))
                .result()
                .get(properties.getIndexName());
        if (mappingRecord == null || mappingRecord.mappings() == null) {
            return;
        }
        Property vectorProperty = mappingRecord.mappings().properties().get("vector");
        if (vectorProperty == null || !vectorProperty.isDenseVector()) {
            return;
        }
        Integer actualDims = vectorProperty.denseVector().dims();
        int expectedDims = embeddingProperties.getDimension();
        if (actualDims != null && actualDims != expectedDims) {
            throw new IllegalStateException("Elasticsearch index " + properties.getIndexName()
                    + " has vector dims " + actualDims
                    + " but embedding model " + embeddingProperties.getModel()
                    + " expects " + expectedDims
                    + ". Delete/recreate the index or use a new index name before re-indexing papers.");
        }
    }
}
