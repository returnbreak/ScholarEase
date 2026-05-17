package com.kesf.backend.service.impl;

import com.kesf.backend.kafka.PaperVectorIndexTask;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * 论文向量索引 Kafka 消费者。
 * <p>
 * 监听 {@code paper-vector-index-topic}，接收由生产者发布的 {@link PaperVectorIndexTask} 消息，
 * 委托给 {@link PaperVectorizationService#index(PaperVectorIndexTask)} 执行完整的向量化流水线：
 * </p>
 * <ol>
 *   <li>从 MinIO 读取 MinerU 解析产物的 content_list_v2.json</li>
 *   <li>调用 {@link PaperChunkBuildService} 将 JSON 拆分为内容块</li>
 *   <li>调用 {@link EmbeddingClient} 对每个内容块向量化</li>
 *   <li>先删除 ES 中该论文的旧数据，再批量写入新向量文档</li>
 * </ol>
 */
@Slf4j // Lombok：自动生成 log 对象（SLF4J）
@Component // 注册为 Spring 组件，使 @KafkaListener 被 Spring Kafka 扫描并启用
@RequiredArgsConstructor
public class PaperVectorIndexConsumer {

    /** 论文向量化服务，封装了完整的 MinIO→分块→Embedding→ES 写入流水线 */
    private final PaperVectorizationService paperVectorizationService;

    private final PaperVectorIndexFailureHandler failureHandler;

    /**
     * 消费论文向量索引任务。
     * <p>
     * Topic 和消费者组 ID 通过 SpEL 表达式从 {@code ScholarEaseKafkaProperties} 动态读取，
     * 而非硬编码，便于通过配置文件切换环境（开发/测试/生产）。
     * </p>
     * <p>
     * 当前消费者配置为 {@code ack-mode: record}（逐条确认），即处理完一条消息后立即提交 offset，
     * 保证了消息处理失败时可重试而不丢失进度。
     * </p>
     *
     * @param task 从 Kafka 反序列化得到的论文向量索引任务
     */
    @KafkaListener(
            topics = "#{@scholarEaseKafkaProperties.topics.paperVectorIndex}", // SpEL 动态引用 Topic 名
            groupId = "#{@scholarEaseKafkaProperties.consumerGroup}"           // SpEL 动态引用消费者组
    )
    public void consume(PaperVectorIndexTask task) {
        log.info("Received paper vector index task: taskId={}, traceId={}, paperMd5={}, contentListObjectKey={}",
                task.getTaskId(),          // 任务 ID（traceId:md5）
                task.getTraceId(),         // 链路追踪 ID
                task.getPaperMd5(),        // 论文 MD5
                task.getContentListObjectKey()); // MinerU 产物文件路径，后续用于读取与向量化

        try {
            paperVectorizationService.index(task);
        } catch (RuntimeException exception) {
            failureHandler.handleFailure(task, exception);
            log.error("Paper vector index failed and upload cleanup was attempted: taskId={}, traceId={}, paperMd5={}",
                    task == null ? null : task.getTaskId(),
                    task == null ? null : task.getTraceId(),
                    task == null ? null : task.getPaperMd5(),
                    exception);
        }
    }
}
