package com.kesf.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kesf.backend.config.EmbeddingProperties;
import com.kesf.backend.service.impl.OpenAiCompatibleEmbeddingClient;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class OpenAiCompatibleEmbeddingClientTests {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private HttpServer server;
    private String capturedRequestBody;
    private String capturedAuthorization;
    private final AtomicInteger activeRequests = new AtomicInteger();
    private final AtomicInteger maxActiveRequests = new AtomicInteger();

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void embedCallsOpenAiCompatibleEndpointWithoutDimensionForBgeM3() throws Exception {
        startEmbeddingServer();

        EmbeddingProperties properties = new EmbeddingProperties();
        properties.setUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/openai");
        properties.setKey("test-key");
        properties.setModel("BAAI/bge-m3");
        properties.setBatchSize(10);
        properties.setTimeoutSeconds(5);

        OpenAiCompatibleEmbeddingClient client = new OpenAiCompatibleEmbeddingClient(properties, objectMapper);

        List<float[]> vectors = client.embed(List.of("The food was delicious and the waiter..."));

        assertThat(vectors).hasSize(1);
        assertThat(vectors.get(0)).containsExactly(0.1f, 0.2f, 0.3f);
        assertThat(capturedAuthorization).isEqualTo("Bearer test-key");

        JsonNode request = objectMapper.readTree(capturedRequestBody);
        assertThat(request.get("model").asText()).isEqualTo("BAAI/bge-m3");
        assertThat(request.get("input").get(0).asText())
                .isEqualTo("The food was delicious and the waiter...");
        assertThat(request.get("encoding_format").asText()).isEqualTo("float");
        assertThat(request.has("dimension")).isFalse();
    }

    @Test
    void embedProcessesBatchesConcurrentlyWhenConcurrencyIsConfigured() throws Exception {
        startEmbeddingServer();

        EmbeddingProperties properties = new EmbeddingProperties();
        properties.setUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/openai");
        properties.setKey("test-key");
        properties.setModel("BAAI/bge-m3");
        properties.setBatchSize(1);
        properties.setMaxConcurrency(2);
        properties.setTimeoutSeconds(5);

        OpenAiCompatibleEmbeddingClient client = new OpenAiCompatibleEmbeddingClient(properties, objectMapper);

        List<float[]> vectors = client.embed(List.of("text-1", "text-2", "text-3"));

        assertThat(vectors).hasSize(3);
        assertThat(maxActiveRequests.get()).isGreaterThanOrEqualTo(2);
    }

    private void startEmbeddingServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/v1/openai/embeddings", exchange -> {
            int active = activeRequests.incrementAndGet();
            maxActiveRequests.updateAndGet(current -> Math.max(current, active));
            try {
                capturedAuthorization = exchange.getRequestHeaders().getFirst("Authorization");
                capturedRequestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                Thread.sleep(150);
                JsonNode input = objectMapper.readTree(capturedRequestBody).get("input");
                StringBuilder data = new StringBuilder();
                for (int i = 0; i < input.size(); i++) {
                    if (i > 0) {
                        data.append(",");
                    }
                    data.append("{\"embedding\":[0.1,0.2,0.3]}");
                }
                byte[] response = ("{\"data\":[" + data + "]}").getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IOException(exception);
            } finally {
                activeRequests.decrementAndGet();
                exchange.close();
            }
        });
        server.start();
    }
}
