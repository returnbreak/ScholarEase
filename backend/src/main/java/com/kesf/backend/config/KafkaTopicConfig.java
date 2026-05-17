package com.kesf.backend.config;

import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Kafka Topic 自动创建配置。
 * <p>
 * 在应用启动时，Spring Kafka 会根据此配置自动在 Kafka 集群中创建所需的 Topic。
 * Topic 分区数和副本数来自 application.yml；生产环境副本数应配置为多副本。
 * </p>
 */
@EnableKafka // 启用 Spring Kafka 支持，包括 @KafkaListener 等注解的自动扫描
@Configuration // 标识为 Spring 配置类，其中的 @Bean 方法会被 Spring 容器管理
@RequiredArgsConstructor // Lombok：为 final 字段自动生成构造器，由 Spring 完成依赖注入
public class KafkaTopicConfig {

    /** 从 application.yml 中读取 scholarease.kafka.* 配置的属性对象 */
    private final ScholarEaseKafkaProperties kafkaProperties;

    /**
     * 创建"论文向量索引"Topic。
     * 生产者将论文元数据和 MinIO 文件路径发布到此 Topic，消费者监听后执行向量化与 Elasticsearch 写入。
     */
    @Bean
    public NewTopic paperVectorIndexTopic() {
        return TopicBuilder.name(kafkaProperties.getTopics().getPaperVectorIndex())
                .partitions(kafkaProperties.getPaperVectorIndexPartitions())
                .replicas(kafkaProperties.getPaperVectorIndexReplicas())
                .build();
    }
}
