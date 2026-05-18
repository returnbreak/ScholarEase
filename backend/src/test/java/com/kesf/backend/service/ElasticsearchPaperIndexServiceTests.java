package com.kesf.backend.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.indices.ElasticsearchIndicesClient;
import co.elastic.clients.elasticsearch.indices.GetMappingResponse;
import co.elastic.clients.elasticsearch.indices.get_mapping.IndexMappingRecord;
import co.elastic.clients.transport.endpoints.BooleanResponse;
import com.kesf.backend.config.EmbeddingProperties;
import com.kesf.backend.config.ScholarEaseElasticsearchProperties;
import com.kesf.backend.service.impl.ElasticsearchPaperIndexService;
import com.kesf.backend.service.impl.PaperVectorDocument;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ElasticsearchPaperIndexServiceTests {

    @Test
    void bulkIndexFailsBeforeWritingWhenExistingIndexVectorDimensionDiffersFromEmbeddingConfig() throws Exception {
        ElasticsearchClient elasticsearchClient = mock(ElasticsearchClient.class);
        ElasticsearchIndicesClient indicesClient = mock(ElasticsearchIndicesClient.class);
        when(elasticsearchClient.indices()).thenReturn(indicesClient);
        when(indicesClient.exists(any(Function.class))).thenReturn(new BooleanResponse(true));
        when(indicesClient.getMapping(any(Function.class))).thenReturn(mappingWithVectorDims(2048));

        ScholarEaseElasticsearchProperties elasticsearchProperties = new ScholarEaseElasticsearchProperties();
        elasticsearchProperties.setIndexName("scholarease_base");
        EmbeddingProperties embeddingProperties = new EmbeddingProperties();
        embeddingProperties.setDimension(1024);
        embeddingProperties.setModel("BAAI/bge-m3");

        ElasticsearchPaperIndexService service = new ElasticsearchPaperIndexService(
                elasticsearchClient,
                elasticsearchProperties,
                embeddingProperties
        );

        assertThatThrownBy(() -> service.bulkIndex(List.of(documentWithVectorLength(1024))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("scholarease_base")
                .hasMessageContaining("2048")
                .hasMessageContaining("1024")
                .hasMessageContaining("BAAI/bge-m3")
                .hasMessageContaining("recreate");

        verify(elasticsearchClient, never()).bulk(any(BulkRequest.class));
    }

    private static GetMappingResponse mappingWithVectorDims(int dims) {
        return GetMappingResponse.of(response -> response.result(
                "scholarease_base",
                IndexMappingRecord.of(record -> record.mappings(mapping -> mapping
                        .properties("vector", property -> property.denseVector(vector -> vector.dims(dims)))))
        ));
    }

    private static PaperVectorDocument documentWithVectorLength(int length) {
        PaperVectorDocument document = new PaperVectorDocument();
        document.setId("doc-1");
        document.setPaperMd5("md5-001");
        document.setVector(new float[length]);
        return document;
    }
}
