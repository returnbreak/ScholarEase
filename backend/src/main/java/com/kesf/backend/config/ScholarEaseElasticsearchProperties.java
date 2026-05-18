package com.kesf.backend.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Elasticsearch 配置属性。
 * <p>
 * 自动绑定 application.yml 中以 {@code elasticsearch} 为前缀的配置项。
 * 当前仅包含索引名称，ES 连接信息（uris/username/password）由 Spring Data Elasticsearch 自动管理。
 * </p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "elasticsearch")
public class ScholarEaseElasticsearchProperties {

    /** Elasticsearch 索引名称，所有论文向量文档写入此索引 */
    private String indexName = "scholarease_bge_m3";
}
