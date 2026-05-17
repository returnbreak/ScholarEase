package com.kesf.backend.service;

import com.kesf.backend.kafka.PaperVectorIndexTask;
import com.kesf.backend.service.impl.PaperVectorIndexFailureHandler;
import com.kesf.backend.service.impl.PaperVectorIndexConsumer;
import com.kesf.backend.service.impl.PaperVectorizationService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link PaperVectorIndexConsumer} 的单元测试。
 * <p>
 * 验证消费者正确将 Kafka 消息委托给 {@link PaperVectorizationService#index(PaperVectorIndexTask)} 处理。
 * </p>
 */
class PaperVectorIndexConsumerTests {

    /**
     * 验证消费者在接收到 Kafka 消息后，将任务原样传递给向量化服务。
     */
    @Test
    void consumeDelegatesTaskToVectorizationService() {
        PaperVectorizationService vectorizationService = mock(PaperVectorizationService.class);
        PaperVectorIndexFailureHandler failureHandler = mock(PaperVectorIndexFailureHandler.class);
        PaperVectorIndexConsumer consumer = new PaperVectorIndexConsumer(vectorizationService, failureHandler);
        PaperVectorIndexTask task = new PaperVectorIndexTask();
        task.setTaskId("trace-001:md5-001");

        consumer.consume(task);

        verify(vectorizationService).index(task);
        verify(failureHandler, never()).handleFailure(eq(task), any(RuntimeException.class));
    }

    @Test
    void consumeCompensatesAndSwallowsWhenVectorIndexFails() {
        PaperVectorizationService vectorizationService = mock(PaperVectorizationService.class);
        PaperVectorIndexFailureHandler failureHandler = mock(PaperVectorIndexFailureHandler.class);
        PaperVectorIndexConsumer consumer = new PaperVectorIndexConsumer(vectorizationService, failureHandler);
        PaperVectorIndexTask task = new PaperVectorIndexTask();
        task.setTaskId("trace-001:md5-001");
        RuntimeException exception = new IllegalStateException("Elasticsearch bulk index failed");
        doThrow(exception).when(vectorizationService).index(task);

        assertThatCode(() -> consumer.consume(task)).doesNotThrowAnyException();

        verify(vectorizationService).index(task);
        verify(failureHandler).handleFailure(task, exception);
    }
}
