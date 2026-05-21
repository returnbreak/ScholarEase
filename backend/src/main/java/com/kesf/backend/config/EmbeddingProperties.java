package com.kesf.backend.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Embedding（文本向量化）API 配置属性。
 * <p>
 * 自动绑定 application.yml 中以 {@code embedding.api} 为前缀的所有配置项。
 * 目前默认对接 DeepInfra OpenAI-compatible Embedding 接口。
 * </p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "embedding.api")
public class EmbeddingProperties {

    /** Embedding API 基础地址（兼容 OpenAI 格式，如 DeepInfra 的 /v1/openai） */
    private String url = "https://api.deepinfra.com/v1/openai";

    /** API Key（通过环境变量 DEEPINFRA_API_TOKEN 或 EMBEDDING_API_KEY 注入，避免硬编码） */
    private String key = "";

    /** 模型名称，默认使用中英跨语言检索表现较好的 BAAI/bge-m3 */
    private String model = "BAAI/bge-m3";

    /** 单次 API 调用最多处理的文本条数，超出的文本会自动分批 */
    private int batchSize = 10;

    /** Embedding 分批调用的最大并发数；调高可提升入库速度，但会增加 API 限流风险。 */
    private int maxConcurrency = 2;

    /** 输出向量维度（需与所选模型支持的维度一致） */
    private int dimension = 1024;

    /** API 调用超时时间（秒），防止网络异常时无限等待 */
    private int timeoutSeconds = 30;
}
