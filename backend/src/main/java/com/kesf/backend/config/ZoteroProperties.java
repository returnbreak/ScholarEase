package com.kesf.backend.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Data
@Component
@ConfigurationProperties(prefix = "zotero")
public class ZoteroProperties {

    private boolean enabled = true;

    private String baseUrl = "http://127.0.0.1:23119";

    private Duration requestTimeout = Duration.ofSeconds(30);

    private Duration metadataPollInterval = Duration.ofSeconds(1);

    private int metadataMaxAttempts = 30;
}
