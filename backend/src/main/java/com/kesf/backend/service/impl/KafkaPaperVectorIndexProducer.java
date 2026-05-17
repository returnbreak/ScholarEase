package com.kesf.backend.service.impl;

import com.kesf.backend.config.ScholarEaseKafkaProperties;
import com.kesf.backend.kafka.PaperVectorIndexTask;
import com.kesf.backend.service.PaperVectorIndexProducer;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

/**
 * 基于 Kafka 的论文向量索引生产者实现。
 * <p>
 * 使用 Spring Kafka 的 {@link KafkaTemplate} 将 {@link PaperVectorIndexTask} 序列化为 JSON
 * 并发送到 {@code paper-vector-index-topic}。消息的 key 为论文 MD5，确保同一论文的任务有序到达同一分区。
 * </p>
 */
@Service // 注册为 Spring Service Bean
@RequiredArgsConstructor // Lombok：为 final 字段自动生成构造器注入
public class KafkaPaperVectorIndexProducer implements PaperVectorIndexProducer {

    /** Spring Kafka 模板，封装了与 Kafka Broker 的通信细节 */
    private final KafkaTemplate<String, PaperVectorIndexTask> kafkaTemplate;

    /** Kafka 配置属性，用于获取 Topic 名称 */
    private final ScholarEaseKafkaProperties kafkaProperties;

    /**
     * 将论文向量化任务发送到 Kafka Topic。
     * <p>
     * 以论文 MD5 作为消息 key，保证同一篇论文的多次操作（索引更新、删除）被路由到同一分区，
     * 从而保证消费顺序，避免并发竞态。
     * </p>
     *
     * @param task 论文向量索引任务
     */
    @Override
    public void send(PaperVectorIndexTask task) {
        kafkaTemplate.send(
                kafkaProperties.getTopics().getPaperVectorIndex(), // 目标 Topic
                task.getPaperMd5(),                                 // 消息 key = 论文 MD5
                task                                                 // 消息体 = 任务对象（自动序列化为 JSON）
        );
    }
}
