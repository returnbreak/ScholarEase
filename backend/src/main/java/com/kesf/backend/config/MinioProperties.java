package com.kesf.backend.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "minio")
public class MinioProperties {

    private String endpoint = "http://localhost:9000";

    private String accessKey = "minioadmin";

    private String secretKey = "minioadmin";

    private String bucketName = "literatures";

    private String publicUrl = "http://localhost:9000";

    private Storage storage = new Storage();

    @Data
    public static class Storage {

        private String originalPrefix = "uploads/{traceId}/original/";

        private String parsedPrefix = "uploads/{traceId}/mineru/";
    }
}
