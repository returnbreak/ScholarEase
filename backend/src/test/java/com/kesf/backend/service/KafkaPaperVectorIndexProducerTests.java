package com.kesf.backend.service;

import com.kesf.backend.config.ScholarEaseKafkaProperties;
import com.kesf.backend.kafka.PaperVectorIndexTask;
import com.kesf.backend.service.impl.KafkaPaperVectorIndexProducer;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.OffsetDateTime;
import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * {@link KafkaPaperVectorIndexProducer} 的单元测试。
 * <p>
 * 验证生产者正确调用 {@link KafkaTemplate#send(String, Object, Object)}，
 * 且传入的 Topic 名称、消息 key 和消息体符合预期。
 * </p>
 */
class KafkaPaperVectorIndexProducerTests {

    /**
     * 验证发送论文向量索引任务时：
     * <ul>
     *   <li>Topic 名称为配置的 "paper-vector-index-topic"</li>
     *   <li>消息 key 为论文 MD5（保证顺序一致性）</li>
     *   <li>消息体为完整的 {@link PaperVectorIndexTask} 对象</li>
     * </ul>
     */
    @Test
    void sendPublishesPaperVectorIndexTaskToConfiguredTopicUsingPaperMd5AsKey() {
        // ---- 准备：Mock KafkaTemplate，配置属性 ----
        @SuppressWarnings("unchecked")
        KafkaTemplate<String, PaperVectorIndexTask> kafkaTemplate = mock(KafkaTemplate.class);
        ScholarEaseKafkaProperties properties = new ScholarEaseKafkaProperties();
        properties.getTopics().setPaperVectorIndex("paper-vector-index-topic");
        KafkaPaperVectorIndexProducer producer = new KafkaPaperVectorIndexProducer(kafkaTemplate, properties);

        // ---- 构造完整的测试任务对象 ----
        PaperVectorIndexTask task = new PaperVectorIndexTask();
        task.setTaskId("trace-001:md5-001");
        task.setTraceId("trace-001");
        task.setPaperMd5("md5-001");
        task.setFileName("attention.pdf");
        task.setFileSizeBytes(100L);
        task.setSubmissionTime(OffsetDateTime.parse("2026-05-13T12:30:45+08:00"));
        task.setMinioBucket("literatures");
        task.setContentListObjectKey("uploads/trace-001/mineru/content_list_v2.json");
        task.setFullMarkdownObjectKey("uploads/trace-001/mineru/full.md");
        task.setTitle("Attention Is All You Need");
        task.setAuthors(List.of("Ashish Vaswani"));
        task.setKeywords(List.of("transformer"));
        task.setLanguage("en");
        task.setYear(2017);
        task.setVenue("NeurIPS");
        task.setDoi("10.5555/3295222.3295349");
        task.setModelVersion("text-embedding-v4");

        // ---- 执行：发送任务 ----
        producer.send(task);

        // ---- 验证：确认 KafkaTemplate.send 被调用，且参数完全匹配 ----
        verify(kafkaTemplate).send("paper-vector-index-topic", "md5-001", task);
    }
}
