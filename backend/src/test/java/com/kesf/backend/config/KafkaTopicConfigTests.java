package com.kesf.backend.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link KafkaTopicConfig} 的单元测试。
 * <p>
 * 验证 Topic 的创建参数（名称、分区数、副本数）从 {@link ScholarEaseKafkaProperties} 正确读取。
 * </p>
 */
class KafkaTopicConfigTests {

    /**
     * 验证 paperVectorIndexTopic 的分区和副本数使用配置属性中的值，
     * 而非硬编码的默认值。
     */
    @Test
    void paperVectorIndexTopicUsesConfiguredPartitionAndReplicaCounts() {
        ScholarEaseKafkaProperties properties = new ScholarEaseKafkaProperties();
        properties.getTopics().setPaperVectorIndex("paper-vector-index-topic");
        properties.setPaperVectorIndexPartitions(2);  // 自定义分区数
        properties.setPaperVectorIndexReplicas(3);     // 自定义副本数
        KafkaTopicConfig config = new KafkaTopicConfig(properties);

        var topic = config.paperVectorIndexTopic();

        assertThat(topic.name()).isEqualTo("paper-vector-index-topic");
        assertThat(topic.numPartitions()).isEqualTo(2);
        assertThat(topic.replicationFactor()).isEqualTo((short) 3);
    }
}
