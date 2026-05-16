package com.kesf.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kesf.backend.config.ZoteroProperties;
import com.kesf.backend.exception.BusinessException;
import com.kesf.backend.exception.ErrorCode;
import com.kesf.backend.service.impl.ZoteroImportServiceImpl;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ZoteroImportServiceImplTests {

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void importParsedPaperPostsPdfBytesToZoteroConnector() throws IOException {
        byte[] pdfBytes = "%PDF-1.7\nScholarEase\n%%EOF".getBytes(StandardCharsets.UTF_8);
        ZoteroServerState state = new ZoteroServerState();
        server = startServer(state);

        ZoteroImportServiceImpl service = new ZoteroImportServiceImpl(properties(server), new ObjectMapper());

        ZoteroImportService.ZoteroImportResult result = service.importParsedPaper(
                pdfBytes,
                "attention.pdf",
                "trace-001"
        );

        assertThat(result.sessionId()).startsWith("scholarease-trace-001-");
        assertThat(result.canRecognize()).isTrue();
        assertThat(result.metadata().title()).isEqualTo("The response of flow duration curves to afforestation");
        assertThat(result.metadata().authors()).containsExactly(
                "Patrick N.J. Lane",
                "Alice E. Best"
        );
        assertThat(result.metadata().language()).isEqualTo("en");
        assertThat(result.metadata().year()).isEqualTo(2005);
        assertThat(result.metadata().venue()).isEqualTo("Journal of Hydrology");
        assertThat(result.metadata().doi()).isEqualTo("10.1016/j.jhydrol.2005.01.006");
        assertThat(result.metadata().keywords()).containsExactly("hydrology");
        assertThat(state.savedAttachment.get().method()).isEqualTo("POST");
        assertThat(state.savedAttachment.get().path()).isEqualTo("/connector/saveStandaloneAttachment");
        assertThat(state.savedAttachment.get().contentType()).isEqualTo("application/pdf");
        assertThat(state.savedAttachment.get().metadata()).contains("\"title\":\"attention\"");
        assertThat(state.savedAttachment.get().metadata()).contains("\"url\":\"scholarease://documents/trace-001\"");
        assertThat(state.savedAttachment.get().body()).isEqualTo(pdfBytes);
    }

    @Test
    void importParsedPaperFailsWhenZoteroConnectorDoesNotCreateAttachment() throws IOException {
        ZoteroServerState state = new ZoteroServerState();
        state.saveAttachmentStatusCode = 500;
        server = startServer(state);
        ZoteroImportServiceImpl service = new ZoteroImportServiceImpl(properties(server), new ObjectMapper());

        assertThatThrownBy(() -> service.importParsedPaper(
                "%PDF-1.7\n%%EOF".getBytes(StandardCharsets.UTF_8),
                "attention.pdf",
                "trace-001"
        ))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.ZOTERO_WRITE_FAILED);
    }

    private static HttpServer startServer(ZoteroServerState state) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/users/0/items", exchange -> {
            String query = exchange.getRequestURI().getQuery();
            byte[] response;
            exchange.getResponseHeaders().add("Last-Modified-Version", "10");
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            if (query != null && query.contains("since=10")) {
                response = """
                        [
                          {
                            "key": "ATTACH1",
                            "data": {
                              "itemType": "attachment",
                              "url": "scholarease://documents/trace-001",
                              "parentItem": "PARENT1"
                            }
                          }
                        ]
                        """.getBytes(StandardCharsets.UTF_8);
            } else {
                response = "[]".getBytes(StandardCharsets.UTF_8);
            }
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.createContext("/api/users/0/items/PARENT1", exchange -> {
            byte[] response = """
                    {
                      "key": "PARENT1",
                      "data": {
                        "itemType": "journalArticle",
                        "title": "The response of flow duration curves to afforestation",
                        "date": "8/2005",
                        "DOI": "10.1016/j.jhydrol.2005.01.006",
                        "language": "en",
                        "publicationTitle": "Journal of Hydrology",
                        "creators": [
                          {"firstName": "Patrick N.J.", "lastName": "Lane", "creatorType": "author"},
                          {"firstName": "Alice E.", "lastName": "Best", "creatorType": "author"}
                        ],
                        "tags": [
                          {"tag": "hydrology"}
                        ]
                      }
                    }
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.createContext("/connector/saveStandaloneAttachment", exchange -> {
            byte[] body = exchange.getRequestBody().readAllBytes();
            state.savedAttachment.set(new CapturedRequest(
                    exchange.getRequestMethod(),
                    exchange.getRequestURI().getPath(),
                    firstHeader(exchange.getRequestHeaders().get("Content-Type")),
                    firstHeader(exchange.getRequestHeaders().get("X-Metadata")),
                    body
            ));
            byte[] response = "{\"canRecognize\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(state.saveAttachmentStatusCode, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        return server;
    }

    private static ZoteroProperties properties(HttpServer server) {
        ZoteroProperties properties = new ZoteroProperties();
        properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.setRequestTimeout(Duration.ofSeconds(5));
        properties.setMetadataMaxAttempts(1);
        properties.setMetadataPollInterval(Duration.ZERO);
        return properties;
    }

    private static String firstHeader(List<String> values) {
        return values == null || values.isEmpty() ? null : values.get(0);
    }

    private record CapturedRequest(
            String method,
            String path,
            String contentType,
            String metadata,
            byte[] body
    ) {
    }

    private static class ZoteroServerState {

        private int saveAttachmentStatusCode = 201;
        private final AtomicReference<CapturedRequest> savedAttachment = new AtomicReference<>();
    }
}
