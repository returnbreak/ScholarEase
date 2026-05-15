package com.kesf.backend.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Data
@Component
@ConfigurationProperties(prefix = "mineru")
public class MinerUProperties {

    private boolean enabled = true;

    private String baseUrl = "https://mineru.net";

    private String token;

    private String modelVersion = "vlm";

    private Api api = new Api();

    private Polling polling = new Polling();

    @Data
    public static class Api {

        private String fileUrlsBatchPath = "/api/v4/file-urls/batch";

        private String batchResultsPath = "/api/v4/extract-results/batch/{batchId}";
    }

    @Data
    public static class Polling {

        private Duration interval = Duration.ofSeconds(5);

        private int maxAttempts = 60;
    }
}
