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
