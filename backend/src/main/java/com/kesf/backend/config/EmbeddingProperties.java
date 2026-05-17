package com.kesf.backend.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Embedding（文本向量化）API 配置属性。
 * <p>
 * 自动绑定 application.yml 中以 {@code embedding.api} 为前缀的所有配置项。
 * 目前对接阿里云 DashScope（灵积）兼容 OpenAI 格式的 Embedding 接口。
 * </p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "embedding.api")
public class EmbeddingProperties {

    /** Embedding API 基础地址（兼容 OpenAI 格式，如 DashScope 的 compatible-mode/v1） */
    private String url = "https://dashscope.aliyuncs.com/compatible-mode/v1";

    /** API Key（通过环境变量 EMBEDDING_API_KEY 注入，避免硬编码） */
    private String key = "sk-0935e8f5a78d40c7833113acbd7979b6";

    /** 模型名称，如 "text-embedding-v4"（DashScope 1024维）或其他兼容模型 */
    private String model = "text-embedding-v4";

    /** 单次 API 调用最多处理的文本条数，超出的文本会自动分批 */
    private int batchSize = 10;

    /** 输出向量维度（需与所选模型支持的维度一致） */
    private int dimension = 2048;

    /** API 调用超时时间（秒），防止网络异常时无限等待 */
    private int timeoutSeconds = 30;
}
