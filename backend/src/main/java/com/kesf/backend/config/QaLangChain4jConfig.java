package com.kesf.backend.config;

import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * LangChain4j 问答配置类。
 * <p>
 * 负责初始化和配置 LangChain4j 相关的组件，主要是为了在多轮对话中管理会话上下文历史。
 * </p>
 */
@Configuration
public class QaLangChain4jConfig {

    /**
     * 配置并注册聊天记忆提供者（ChatMemoryProvider）。
     * <p>
     * 每次与大模型交互时，LangChain4j 的 AiServices 会调用该提供者，为当前会话（基于 memoryId）
     * 动态创建并提供聊天记忆（ChatMemory）实例。
     * </p>
     *
     * @param chatMemoryStore 聊天记忆的底层存储实现，当前工程中注入的是 RedisChatMemoryStore，用于将会话持久化到 Redis。
     * @param qaProperties    问答相关的配置属性，从中读取 yml 配置文件里对上下文轮次的限制。
     * @return ChatMemoryProvider 提供者对象
     */
    @Bean
    public ChatMemoryProvider chatMemoryProvider(ChatMemoryStore chatMemoryStore,
                                                 QaProperties qaProperties) {
        // 计算滑动窗口需要保留的最大消息数。
        // 因为一轮对话通常包含 1 条用户提问（UserMessage） + 1 条 AI 答复（AiMessage），所以历史轮次数要乘以 2。
        // 使用 Math.max(2, ...) 作为兜底，确保无论配置如何，至少都会保留 1 轮（2条）对话。
        int maxMessages = Math.max(2, qaProperties.getHistoryRecentTurns() * 2);
        
        // 返回 ChatMemoryProvider 的 Lambda 实现。
        // 当传入对应的 memoryId（会话ID）时，它会构建并返回一个带滑动窗口的聊天记忆对象。
        return memoryId -> MessageWindowChatMemory.builder()
                .id(memoryId) // 设置会话 ID，这样底层 Store（如 Redis）在存取时就能区分出是哪个用户的会话
                .maxMessages(maxMessages) // 设置滑动窗口最大容量，当对话消息总数超过此值时，最老的记录会被自动清理出上下文
                .chatMemoryStore(chatMemoryStore) // 绑定实际的存储介质，确保历史对话数据不会因为应用重启而丢失
                .build();
    }
}
