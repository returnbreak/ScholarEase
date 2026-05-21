package com.kesf.backend.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.util.ObjectBuilder;
import com.kesf.backend.config.QaProperties;
import com.kesf.backend.config.ScholarEaseElasticsearchProperties;
import com.kesf.backend.service.impl.PaperVectorDocument;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PaperRetrievalSearchServiceTests {

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void vectorThenBm25RescoreUsesTranslatedBm25QueryWithoutPermissionFilters() throws Exception {
        ElasticsearchClient elasticsearchClient = mock(ElasticsearchClient.class);
        EmbeddingClient embeddingClient = mock(EmbeddingClient.class);

        ScholarEaseElasticsearchProperties elasticsearchProperties = new ScholarEaseElasticsearchProperties();
        elasticsearchProperties.setIndexName("scholarease_bge_m3");

        QaProperties qaProperties = new QaProperties();
        qaProperties.setVectorNumCandidates(64);

        PaperVectorDocument document = document("doc-1", "paper-md5", 3, "multi-head attention");

        when(embeddingClient.embed(List.of("Transformer 论文中 multi-head attention 的作用是什么？")))
                .thenReturn(List.of(new float[]{0.1f, 0.2f}));
        when(elasticsearchClient.search(
                org.mockito.ArgumentMatchers.any(Function.class),
                eq(PaperVectorDocument.class)
        )).thenReturn(searchResponse(document));

        PaperRetrievalSearchService service = new PaperRetrievalSearchService(
                elasticsearchClient,
                embeddingClient,
                elasticsearchProperties,
                qaProperties
        );

        PaperRetrievalSearchService.RetrievalQuery query =
                new PaperRetrievalSearchService.RetrievalQuery(
                        "Transformer 论文中 multi-head attention 的作用是什么？",
                        "What is the role of multi-head attention in the Transformer paper?",
                        List.of("multi-head attention", "Transformer"),
                        List.of("multi-head attention")
                );

        List<PaperRetrievalSearchService.PaperRetrievalHit> hits =
                service.searchByVectorThenBm25Rescore(query, 8);

        ArgumentCaptor<Function<SearchRequest.Builder, ObjectBuilder<SearchRequest>>> captor =
                ArgumentCaptor.forClass(Function.class);
        verify(elasticsearchClient).search(captor.capture(), eq(PaperVectorDocument.class));
        SearchRequest request = captor.getValue().apply(new SearchRequest.Builder()).build();

        assertThat(request.index()).containsExactly("scholarease_bge_m3");
        assertThat(request.knn()).hasSize(1);
        assertThat(request.knn().get(0).field()).isEqualTo("vector");
        assertThat(request.knn().get(0).k()).isEqualTo(64);
        assertThat(request.knn().get(0).numCandidates()).isEqualTo(64);
        assertThat(request.knn().get(0).queryVector()).containsExactly(0.1f, 0.2f);

        assertThat(request.query()).isNotNull();
        assertThat(request.query().multiMatch().query())
                .contains("multi-head attention")
                .contains("Transformer");
        assertThat(request.rescore()).hasSize(1);
        assertThat(request.rescore().get(0).windowSize()).isEqualTo(64);

        assertThat(request.query().isBool()).isFalse();
        assertThat(request.query().isMultiMatch()).isTrue();
        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).document().getPaperMd5()).isEqualTo("paper-md5");
        assertThat(hits.get(0).source()).isEqualTo(PaperRetrievalSearchService.RetrievalSource.RESCORED);
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void hybridSearchMergesVectorAndBm25WithRrf() throws Exception {
        ElasticsearchClient elasticsearchClient = mock(ElasticsearchClient.class);
        EmbeddingClient embeddingClient = mock(EmbeddingClient.class);
        ScholarEaseElasticsearchProperties elasticsearchProperties = new ScholarEaseElasticsearchProperties();
        QaProperties qaProperties = new QaProperties();
        qaProperties.setVectorTopK(8);
        qaProperties.setVectorNumCandidates(64);
        qaProperties.setBm25TopK(8);
        qaProperties.setMergedTopK(8);

        when(embeddingClient.embed(List.of("中文问题"))).thenReturn(List.of(new float[]{0.1f, 0.2f}));
        when(elasticsearchClient.search(
                org.mockito.ArgumentMatchers.any(Function.class),
                eq(PaperVectorDocument.class)
        ))
                .thenReturn(searchResponse(document("v1", "paper-a", 1, "semantic hit")))
                .thenReturn(searchResponse(document("b1", "paper-a", 1, "keyword hit")));

        PaperRetrievalSearchService service = new PaperRetrievalSearchService(
                elasticsearchClient,
                embeddingClient,
                elasticsearchProperties,
                qaProperties
        );

        List<PaperRetrievalSearchService.PaperRetrievalHit> hits = service.searchHybrid(
                new PaperRetrievalSearchService.RetrievalQuery(
                        "中文问题",
                        "english query",
                        List.of("keyword"),
                        List.of("keyword")
                )
        );

        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).source()).isEqualTo(PaperRetrievalSearchService.RetrievalSource.HYBRID);
        assertThat(hits.get(0).vectorRank()).isEqualTo(1);
        assertThat(hits.get(0).bm25Rank()).isEqualTo(1);
        assertThat(hits.get(0).rrfScore()).isGreaterThan(0);
    }

    @Test
    void hybridSearchRunsVectorAndBm25RecallConcurrently() {
        PaperRetrievalSearchService service = new PaperRetrievalSearchService(
                mock(ElasticsearchClient.class),
                mock(EmbeddingClient.class),
                new ScholarEaseElasticsearchProperties(),
                new QaProperties()
        ) {
            private final CountDownLatch bm25Started = new CountDownLatch(1);
            private final AtomicBoolean bm25WasStartedWhileVectorWaited = new AtomicBoolean(false);

            @Override
            public List<PaperRetrievalHit> searchByVector(String query, int topK, int numCandidates) {
                try {
                    bm25WasStartedWhileVectorWaited.set(bm25Started.await(300, TimeUnit.MILLISECONDS));
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
                assertThat(bm25WasStartedWhileVectorWaited).isTrue();
                return List.of(hit(document("v1", "paper-a", 1, "semantic hit"),
                        RetrievalSource.VECTOR, 1, null));
            }

            @Override
            public List<PaperRetrievalHit> searchByBm25(String queryText, List<String> exactTerms, int topK) {
                bm25Started.countDown();
                return List.of(hit(document("b1", "paper-b", 1, "keyword hit"),
                        RetrievalSource.BM25, null, 1));
            }
        };

        List<PaperRetrievalSearchService.PaperRetrievalHit> hits = service.searchHybrid(
                new PaperRetrievalSearchService.RetrievalQuery(
                        "中文问题",
                        "english query",
                        List.of("keyword"),
                        List.of("keyword")
                )
        );

        assertThat(hits).hasSize(2);
    }

    private static PaperVectorDocument document(String id, String paperMd5, int chunkIndex, String rawText) {
        PaperVectorDocument document = new PaperVectorDocument();
        document.setId(id);
        document.setPaperMd5(paperMd5);
        document.setChunkIndex(chunkIndex);
        document.setRawText(rawText);
        document.setContentHash("hash-" + chunkIndex);
        return document;
    }

    private static SearchResponse<PaperVectorDocument> searchResponse(PaperVectorDocument document) {
        return SearchResponse.of(response -> response
                .took(1)
                .timedOut(false)
                .shards(shards -> shards.total(1).successful(1).failed(0))
                .hits(hits -> hits
                        .hits(hit -> hit
                                .index("scholarease_bge_m3")
                                .id(document.getId())
                                .score(0.72d)
                                .source(document)
                        )
                )
        );
    }

    private static PaperRetrievalSearchService.PaperRetrievalHit hit(
            PaperVectorDocument document,
            PaperRetrievalSearchService.RetrievalSource source,
            Integer vectorRank,
            Integer bm25Rank
    ) {
        return new PaperRetrievalSearchService.PaperRetrievalHit(
                document,
                0.72d,
                1,
                source,
                vectorRank,
                bm25Rank,
                0.0d
        );
    }
}
