package com.kesf.backend.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * ScholarEase Kafka 配置属性。
 * <p>
 * 自动绑定 application.yml 中以 {@code scholarease.kafka} 为前缀的所有配置项。
 * 支持通过配置文件覆盖默认值，实现 Topic 名称和消费者组的灵活管理。
 * </p>
 */
@Data // Lombok：自动生成 getter/setter/toString/equals/hashCode
@Component // 注册为 Spring Bean，使 @ConfigurationProperties 绑定生效
@ConfigurationProperties(prefix = "scholarease.kafka") // 绑定 scholarease.kafka.* 配置项
public class ScholarEaseKafkaProperties {

    /** 消费者组 ID，用于 Kafka 消费组标识，同一组内的消费者共享消费进度 */
    private String consumerGroup = "paper-vector-index-group";

    /** 论文向量索引 Topic 分区数 */
    private int paperVectorIndexPartitions = 1;

    /** 论文向量索引 Topic 副本数。生产环境应配置为多副本，且不超过 Kafka broker 数量 */
    private int paperVectorIndexReplicas = 3;

    /** 内嵌的 Topic 名称配置 */
    private Topics topics = new Topics();

    /**
     * Topic 名称内部类。
     * 集中管理所有 Kafka Topic 的名称，避免硬编码字符串分散在各处。
     */
    @Data
    public static class Topics {

        /** 论文向量索引 Topic：生产者发布向量化任务，消费者执行向量化并写入 Elasticsearch */
        private String paperVectorIndex = "paper-vector-index-topic";
    }
}
