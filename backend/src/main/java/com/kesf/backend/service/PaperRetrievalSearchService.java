package com.kesf.backend.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import com.kesf.backend.config.QaProperties;
import com.kesf.backend.config.ScholarEaseElasticsearchProperties;
import com.kesf.backend.service.impl.PaperVectorDocument;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 面向问答（QA）系统的只读论文检索服务。
 *
 * <p>具体实现参考了 PaiSmart 的混合检索思想，并针对 ScholarEase 做了适配：
 * 将用户的中文问题直接向量化用于语义召回（Vector Recall），
 * 将经过大模型翻译后的英文 Query 和提取出的专有名词（Exact Terms）用于 BM25 关键词召回。
 * 在 QA 的第一个版本中，暂时不加入复杂的权限控制和组织过滤逻辑。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaperRetrievalSearchService {

    // 定义 BM25 检索时参与匹配的字段及其权重（Boost）
    // 权重越高的字段命中，得分越高。例如原文(rawText)权重最高为3，标题(title)权重为2
    private static final List<String> BM25_FIELDS = List.of(
            "rawText^3",
            "textContent^2",
            "title^2",
            "sectionPath^1.5",
            "authorText",
            "venue",
            "doi"
    );

    // Elasticsearch 官方 Java 客户端
    private final ElasticsearchClient elasticsearchClient;
    // 用于将文本转化为向量的 Embedding 客户端接口
    private final EmbeddingClient embeddingClient;
    // ES 相关的配置（如索引名）
    private final ScholarEaseElasticsearchProperties elasticsearchProperties;
    // 问答 RAG 相关的配置（如检索 Top-K 大小、RRF 参数等）
    private final QaProperties qaProperties;

    /**
     * 核心混合检索方法（双路召回 + RRF 融合）。
     *
     * @param query 经过意图识别和重写后的结构化查询条件
     * @return 经过排序后的混合检索命中结果列表
     */
    public List<PaperRetrievalHit> searchHybrid(RetrievalQuery query) {
        if (query == null || !StringUtils.hasText(query.vectorQuery())) {
            return List.of();
        }
        // 1. 发起向量语义召回
        List<PaperRetrievalHit> vectorHits = searchByVector(
                query.vectorQuery(),
                qaProperties.getVectorTopK(),
                qaProperties.getVectorNumCandidates()
        );
        // 2. 发起 BM25 关键词召回
        List<PaperRetrievalHit> bm25Hits = searchByBm25(
                query.bm25QueryText(),
                query.exactTerms(),
                qaProperties.getBm25TopK()
        );
        // 3. 使用 RRF 倒数排名融合算法，将两路结果依据排名进行合并打分
        List<PaperRetrievalHit> merged = mergeByRrf(vectorHits, bm25Hits);
        // 4. 截断保留得分最高的前 Top-K 个结果
        int limit = Math.max(1, qaProperties.getMergedTopK());
        return merged.stream().limit(limit).toList();
    }

    /**
     * Elasticsearch 原生的混合检索方式（备用方案）。
     * 类似于 PaiSmart 中先进行 kNN 召回，再进行 Rescore（重打分）的过程。
     * <p>
     * 当我们希望在一个单独的 ES 请求中，利用 BM25 在向量召回的候选窗口里进行重新排序时，
     * 可以使用这个方法。由于灵活性和多路召回的控制需求，主流程通常走在业务层实现的双路并发 RRF 融合。
     * </p>
     */
    public List<PaperRetrievalHit> searchByVectorThenBm25Rescore(RetrievalQuery query, int topK) {
        if (query == null || !StringUtils.hasText(query.vectorQuery())) {
            return List.of();
        }
        try {
            // 文本转向量
            List<Float> queryVector = embedToVectorList(query.vectorQuery());
            String bm25Query = query.bm25QueryText();
            int safeTopK = Math.max(1, topK);
            // 决定底层 kNN 需要召回的候选集大小
            int recallK = Math.max(safeTopK, qaProperties.getVectorNumCandidates());

            SearchResponse<PaperVectorDocument> response = elasticsearchClient.search(search -> {
                        search.index(elasticsearchProperties.getIndexName())
                                .knn(knn -> knn
                                        .field("vector")
                                        .queryVector(queryVector)
                                        .k(recallK)
                                        .numCandidates(recallK)
                                )
                                .size(safeTopK);
                        // 如果有 BM25 查询条件，加入 ES 的 rescore 逻辑
                        if (StringUtils.hasText(bm25Query)) {
                            search.query(q -> q.multiMatch(mm -> mm
                                    .query(bm25Query)
                                    .fields(BM25_FIELDS)
                            ));
                            search.rescore(rescore -> rescore
                                    .windowSize(recallK)
                                    .query(rq -> rq
                                            // 原始向量得分占比 20%，BM25 得分占比 100%
                                            .queryWeight(0.2d)
                                            .rescoreQueryWeight(1.0d)
                                            .query(rqq -> rqq.multiMatch(mm -> mm
                                                    .query(bm25Query)
                                                    .fields(BM25_FIELDS)
                                            ))
                                    )
                            );
                        }
                        return search;
                    },
                    PaperVectorDocument.class
            );
            return toHits(response, RetrievalSource.RESCORED, null);
        } catch (Exception exception) {
            throw new IllegalStateException("Paper hybrid rescore retrieval failed: "
                    + exception.getMessage(), exception);
        }
    }

    /**
     * 使用默认参数执行纯向量检索。
     */
    public List<PaperRetrievalHit> searchByVector(String query) {
        return searchByVector(query, qaProperties.getVectorTopK(), qaProperties.getVectorNumCandidates());
    }

    /**
     * 自定义参数的纯向量语义检索。
     *
     * @param query         用户的搜索文本（通常是中文）
     * @param topK          期望返回的最终结果数
     * @param numCandidates ES 执行 kNN 检索时从每个分片中提取的底层候选集大小
     */
    public List<PaperRetrievalHit> searchByVector(String query, int topK, int numCandidates) {
        if (!StringUtils.hasText(query)) {
            return List.of();
        }
        try {
            // 将查询文本转换为向量
            List<Float> queryVector = embedToVectorList(query);
            int safeTopK = Math.max(1, topK);
            int safeNumCandidates = Math.max(safeTopK, numCandidates);

            // 使用 Elasticsearch 8 API 构建并发送 kNN 检索请求
            SearchResponse<PaperVectorDocument> response = elasticsearchClient.search(search -> search
                            .index(elasticsearchProperties.getIndexName())
                            .knn(knn -> knn
                                    .field("vector")
                                    .queryVector(queryVector)
                                    .k(safeTopK)
                                    .numCandidates(safeNumCandidates)
                            )
                            .size(safeTopK),
                    PaperVectorDocument.class
            );
            // 将结果转换为业务对象，同时记录该文档在向量召回列表中的排名(vectorRank)
            return toHits(response, RetrievalSource.VECTOR, PaperRetrievalHit::withVectorRank);
        } catch (Exception exception) {
            throw new IllegalStateException("Paper vector retrieval failed: "
                    + exception.getMessage(), exception);
        }
    }

    /**
     * 纯 BM25 关键词精确匹配检索。
     *
     * @param queryText  英文长难句 query，用于广度匹配
     * @param exactTerms 精确专有名词列表，用于深度加权匹配
     * @param topK       期望返回的结果数
     */
    public List<PaperRetrievalHit> searchByBm25(String queryText, List<String> exactTerms, int topK) {
        if (!StringUtils.hasText(queryText)) {
            return List.of();
        }
        try {
            int safeTopK = Math.max(1, topK);
            SearchResponse<PaperVectorDocument> response = elasticsearchClient.search(search -> search
                            .index(elasticsearchProperties.getIndexName())
                            .query(query -> query.bool(bool -> {
                                // 基础的 Multi-match：在标题、内容、作者等多字段匹配检索词
                                bool.should(should -> should.multiMatch(mm -> mm
                                        .query(queryText)
                                        .fields(BM25_FIELDS)
                                ));
                                // 强化术语匹配：如果提取到了专有名词，使用 match_phrase 进行精准短语匹配并提升2倍权重
                                if (exactTerms != null) {
                                    exactTerms.stream()
                                            .filter(StringUtils::hasText)
                                            .forEach(term -> bool.should(should -> should.matchPhrase(phrase -> phrase
                                                    .field("textContent")
                                                    .query(term)
                                                    .boost(2.0f)
                                            )));
                                }
                                // 确保上述的 should 条件中至少要满足 1 个
                                return bool.minimumShouldMatch("1");
                            }))
                            .size(safeTopK),
                    PaperVectorDocument.class
            );
            // 将结果转换为业务对象，同时记录该文档在 BM25 召回列表中的排名(bm25Rank)
            return toHits(response, RetrievalSource.BM25, PaperRetrievalHit::withBm25Rank);
        } catch (Exception exception) {
            throw new IllegalStateException("Paper BM25 retrieval failed: "
                    + exception.getMessage(), exception);
        }
    }

    /**
     * 使用 RRF (Reciprocal Rank Fusion) 算法融合两路召回结果。
     * RRF 认为：文档越靠前（Rank越小），它越相关。通过倒数累加，使得在多路中都排在前面的文档脱颖而出。
     */
    private List<PaperRetrievalHit> mergeByRrf(List<PaperRetrievalHit> vectorHits,
                                               List<PaperRetrievalHit> bm25Hits) {
        Map<String, PaperRetrievalHit> merged = new LinkedHashMap<>();
        // 分别计算向量结果和 BM25 结果的 RRF 分数并存入 Map，遇到相同的文档会自动累加分数
        addRrfScores(merged, vectorHits, true);
        addRrfScores(merged, bm25Hits, false);
        AtomicInteger rank = new AtomicInteger(1);
        
        // 最终按照累加后的 RRF 分数降序排列，并重新赋予最终的 Rank
        return merged.values().stream()
                .sorted(Comparator.comparing(PaperRetrievalHit::rrfScore).reversed())
                .map(hit -> hit.withRank(rank.getAndIncrement()).withSource(resolveSource(hit)))
                .toList();
    }

    /**
     * 计算单路结果的 RRF 分数并追加到合并池中。
     * 核心公式： Score = 1.0 / (k + rank)
     */
    private void addRrfScores(Map<String, PaperRetrievalHit> merged,
                              List<PaperRetrievalHit> hits,
                              boolean vector) {
        if (hits == null) {
            return;
        }
        // 获取 RRF 算法的平滑常数 k（通常配置为 60）。使用 Math.max(1, ...) 兜底，防止出现除以 0 或负数的情况。
        int rrfK = Math.max(1, qaProperties.getRrfK());
        
        // 遍历当前这一路（如纯向量召回或纯 BM25 召回）命中的所有文档结果
        for (PaperRetrievalHit hit : hits) {
            // 生成当前文档片段的唯一标识（依靠 PaperMd5 + 分块 Index + 内容 Hash），用于跨路去重
            String key = documentKey(hit.document());
            // 根据当前执行合并的是哪一路（布尔值 vector），提取出该文档在这一路中的实际名次
            int sourceRank = vector ? hit.vectorRank() : hit.bm25Rank();
            
            // 【核心算法】计算该文档在这条检索路上的 RRF 倒数排名分数：Score = 1.0 / (k + rank)
            // 名次越靠前（rank 越小），得到的分数越高
            double score = 1.0d / (rrfK + Math.max(1, sourceRank));
            
            // 检查这个文档片段是否已经被其他检索路（如刚才的向量检索）找到并存入合并池了
            PaperRetrievalHit existing = merged.get(key);
            if (existing == null) {
                // 情况A：首次出现。直接把刚算好的 RRF 分数赋给它，并放入全局合并池
                merged.put(key, hit.withRrfScore(score));
            } else {
                // 情况B：双路命中（另一个检索路已经召回过该文档）。此时将新算出的分数与原有分数【累加】！
                PaperRetrievalHit combined = existing.withRrfScore(existing.rrfScore() + score);
                // 并且，安全地保留它在两条路上各自的原始排名信息，防止互相覆盖
                if (vector) {
                    combined = combined.withVectorRank(sourceRank);
                } else {
                    combined = combined.withBm25Rank(sourceRank);
                }
                merged.put(key, combined);
            }
        }
    }

    /**
     * 解析一个命中结果究竟是哪边查出来的（向量、BM25还是双路命中）
     */
    private RetrievalSource resolveSource(PaperRetrievalHit hit) {
        if (hit.vectorRank() != null && hit.bm25Rank() != null) {
            return RetrievalSource.HYBRID; // 双路同时命中
        }
        if (hit.vectorRank() != null) {
            return RetrievalSource.VECTOR; // 仅向量检索命中
        }
        if (hit.bm25Rank() != null) {
            return RetrievalSource.BM25;   // 仅 BM25 检索命中
        }
        return RetrievalSource.RESCORED;   // ES 原生 Rescore 结果
    }

    /**
     * 将 Elasticsearch 的原生搜索响应（SearchResponse）转换为业务层通用的检索命中结果（PaperRetrievalHit）列表。
     *
     * @param response   ES 客户端返回的搜索响应对象
     * @param source     当前检索结果的来源（例如：纯向量检索 VECTOR，纯关键词检索 BM25）
     * @param rankMarker 一个函数式接口，用于在生成结果时，将当前的排名（Rank）记录到具体的来源排序字段（如 vectorRank 或 bm25Rank）中
     * @return 转换后的检索命中结果列表
     */
    private List<PaperRetrievalHit> toHits(SearchResponse<PaperVectorDocument> response,
                                           RetrievalSource source,
                                           RankMarker rankMarker) {
        // 使用 AtomicInteger 作为一个可变的计数器，用于记录当前文档在检索结果列表中的排名（从 1 开始计）
        AtomicInteger rank = new AtomicInteger(1);
        
        // 获取所有命中的 ES 文档，并转化为 Stream 以便进行链式处理
        return response.hits().hits().stream()
                // 过滤掉没有实际内容的无效命中结果（防御性编程，防止空指针）
                .filter(hit -> hit.source() != null)
                .map(hit -> {
                    // 获取并递增当前的排名（类似于 i++ 的效果）
                    int currentRank = rank.getAndIncrement();
                    
                    // 构建基础的业务命中实体
                    PaperRetrievalHit retrievalHit = new PaperRetrievalHit(
                            hit.source(), // ES 中实际存储的论文内容块文档（PaperVectorDocument）
                            hit.score(),  // 原生的 ES 匹配得分（如向量相似度得分或 BM25 相关性得分）
                            currentRank,  // 当前文档在这一路检索列表里的排名
                            source,       // 标记命中来源
                            null,         // 向量排名初始占位（稍后由 rankMarker 填充）
                            null,         // BM25排名初始占位（稍后由 rankMarker 填充）
                            0.0d          // RRF 融合分数占位（在后续 mergeByRrf 方法中进行计算和覆盖）
                    );
                    
                    // 如果传入了排名标记器（rankMarker），则调用它将排名保存到特定的字段（vectorRank或bm25Rank）；否则直接返回
                    return rankMarker == null ? retrievalHit : rankMarker.mark(retrievalHit, currentRank);
                })
                // 将 Stream 收集并转为不可变的 List 返回
                .toList();
    }

    /**
     * 调用大模型 API 生成文本的浮点型向量列表
     */
    private List<Float> embedToVectorList(String text) {
        List<float[]> vectors = embeddingClient.embed(List.of(text));
        if (vectors == null || vectors.isEmpty()) {
            throw new IllegalStateException("Query embedding result is empty");
        }
        float[] rawVector = vectors.get(0);
        List<Float> vector = new ArrayList<>(rawVector.length);
        for (float value : rawVector) {
            vector.add(value);
        }
        return vector;
    }

    /**
     * 根据文档的各项 Hash 生成在 RRF 合并中的唯一区分主键。
     * 只有 MD5、块索引和哈希均相同，才认为是同一个论文的内容块。
     */
    private String documentKey(PaperVectorDocument document) {
        String paperMd5 = document.getPaperMd5() == null ? "" : document.getPaperMd5();
        String chunkIndex = document.getChunkIndex() == null ? "" : document.getChunkIndex().toString();
        String contentHash = document.getContentHash() == null ? "" : document.getContentHash();
        return paperMd5 + "#" + chunkIndex + "#" + contentHash;
    }

    /**
     * 标记当前文档在对应检索路中排名的函数式接口。
     */
    private interface RankMarker {
        PaperRetrievalHit mark(PaperRetrievalHit hit, int rank);
    }

    /**
     * 标识一条命中结果的来源枚举。
     */
    public enum RetrievalSource {
        VECTOR,
        BM25,
        HYBRID,
        RESCORED
    }

    /**
     * 封装供底层混合检索使用的查询参数对象。
     * 该对象的数据通常由上游的意图识别与 Query 重写模块（QueryTranslationService）生成。
     */
    public record RetrievalQuery(
            // 用于向量相似度语义召回的查询文本（在当前业务中，通常保留用户的原始中文提问）
            String vectorQuery,
            // 经过大模型翻译或重写后的英文查询长句，用于跨语言的 BM25 检索
            String queryEn,
            // 提取出的一些用于增强 BM25 命中率的离散关键词列表
            List<String> bm25Keywords,
            // 需要进行深度加权和精准匹配（match_phrase）的专有名词或特定缩写（如 "GPT-4"）
            List<String> exactTerms
    ) {
        /**
         * 辅助方法：组装用于最终 BM25 检索的完整查询文本。
         * 它的作用是将英文长句（queryEn）和各个独立的关键字（bm25Keywords）用空格无缝拼接在一起。
         */
        public String bm25QueryText() {
            List<String> parts = new ArrayList<>();
            if (StringUtils.hasText(queryEn)) {
                parts.add(queryEn);
            }
            if (bm25Keywords != null) {
                bm25Keywords.stream()
                        .filter(StringUtils::hasText)
                        .forEach(parts::add);
            }
            return String.join(" ", parts);
        }
    }

    /**
     * 封装命中的文献块信息及打分明细。
     */
    public record PaperRetrievalHit(
            PaperVectorDocument document, // 文档实体信息
            Double score,                 // ES 原生相似度/匹配度分数
            Integer rank,                 // 当前排名
            RetrievalSource source,       // 命中来源
            Integer vectorRank,           // 如果向量命中，在向量路中的排名
            Integer bm25Rank,             // 如果BM25命中，在BM25路中的排名
            Double rrfScore               // 合并重打分后的 RRF 分数
    ) {
        PaperRetrievalHit withRank(Integer rank) {
            return new PaperRetrievalHit(document, score, rank, source, vectorRank, bm25Rank, rrfScore);
        }

        PaperRetrievalHit withSource(RetrievalSource source) {
            return new PaperRetrievalHit(document, score, rank, source, vectorRank, bm25Rank, rrfScore);
        }

        PaperRetrievalHit withVectorRank(Integer vectorRank) {
            return new PaperRetrievalHit(document, score, rank, source, vectorRank, bm25Rank, rrfScore);
        }

        PaperRetrievalHit withBm25Rank(Integer bm25Rank) {
            return new PaperRetrievalHit(document, score, rank, source, vectorRank, bm25Rank, rrfScore);
        }

        PaperRetrievalHit withRrfScore(Double rrfScore) {
            return new PaperRetrievalHit(document, score, rank, source, vectorRank, bm25Rank, rrfScore);
        }
    }
}
