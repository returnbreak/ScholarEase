package com.kesf.backend.service;

import com.kesf.backend.kafka.PaperVectorIndexTask;

/**
 * 论文向量索引生产者接口。
 * <p>
 * 定义将论文向量化任务发送到消息队列的契约。调用方（如 {@code DocumentServiceImpl}）
 * 在论文上传解析完成后调用 {@link #send(PaperVectorIndexTask)}，由实现类负责投递到 Kafka Topic。
 * </p>
 */
public interface PaperVectorIndexProducer {

    /**
     * 发送一条论文向量索引任务到消息队列。
     *
     * @param task 包含论文元数据和解析产物路径的向量化任务对象
     */
    void send(PaperVectorIndexTask task);
}
