package com.kesf.backend.service.qa;

import com.kesf.backend.config.QaProperties;
import com.kesf.backend.dto.qa.QaChatStreamRequest;
import com.kesf.backend.dto.qa.QaCitationDTO;
import com.kesf.backend.dto.qa.QaStreamMetadataDTO;
import com.kesf.backend.dto.qa.QaWebSocketResponse;
import com.kesf.backend.service.PaperRetrievalSearchService;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * QA Agent 核心服务类。
 * <p>
 * 负责编排检索增强生成（RAG）的完整工作流。
 * 主要流程包括：用户意图识别与重写(Query Rewrite) -> 混合知识检索 -> 截断与证据组装 ->
 * 提前推送元数据 -> 构建历史上下文 -> 调用大模型 -> 实时流式响应（WebSocket）-> 对话记忆持久化。
 * </p>
 **/
@Slf4j
@Service
@RequiredArgsConstructor
public class QaAgentService {

    // LangChain4j 流式聊天模型接口（底层可能配置为 DeepSeek、OpenAI 等适配器）
    private final StreamingChatModel streamingChatModel;
    // 会话记忆提供者，用于根据 sessionId 获取或创建当前会话的历史对话上下文
    private final ChatMemoryProvider chatMemoryProvider;
    // 问题翻译/重写服务，将用户原始中/英文提问转为适合进行 BM25 和向量检索的结构化 Query
    private final QueryTranslationService queryTranslationService;
    // 论文检索服务，负责执行底层向量和关键词的混合检索，并做 RRF 重排序
    private final PaperRetrievalSearchService retrievalSearchService;
    // 引用组装器，用于将底层检索到的 Document 组装为给前端展示的元数据以及喂给大模型的 Prompt
    private final CitationAssembler citationAssembler;
    // QA 相关的业务配置，用于读取 Token 和上下文截断等参数
    private final QaProperties qaProperties;

    /**
     * 核心流式问答接口，使用 WebSocket 向前端推送“打字机”实时输出效果。
     *
     * @param request 前端传来的请求体，包含 sessionId 和用户的 message
     * @param sender  发送响应的消费者回调（由 WebSocketHandler 传入，用于向客户端推送事件流）
     */
    public void streamAnswer(QaChatStreamRequest request, Consumer<QaWebSocketResponse> sender) {
        String sessionId = ensureSessionId(request.getSessionId());
        String messageId = UUID.randomUUID().toString();
        // 生成用于全链路问题排查的 traceId
        String qaTraceId = UUID.randomUUID().toString().replace("-", "");

        try {
            // 1. 意图理解与重写：提取出中英文独立问题、确切词汇等
            QueryTranslationService.QueryRewriteResult rewrite =
                    queryTranslationService.rewrite(request.getMessage());
            // 2. 知识检索：调用混合检索获取相关度最高的论文片段
            List<PaperRetrievalSearchService.PaperRetrievalHit> hits =
                    retrievalSearchService.searchHybrid(rewrite.toRetrievalQuery());
            // 3. 截断限制：为了防止大模型 Token 溢出，根据配置文件的限制保留 Top N 块片段
            int finalLimit = Math.max(1, qaProperties.getFinalContextMaxChunks());
            List<PaperRetrievalSearchService.PaperRetrievalHit> finalHits = hits.stream()
                    .limit(finalLimit)
                    .toList();
            // 4. 组装证据：将其转换为可供前端查阅的 Citation 对象，以及喂给 LLM 阅读的纯文本证据
            List<QaCitationDTO> citations = citationAssembler.toCitations(finalHits);
            String evidence = citationAssembler.toEvidencePrompt(citations);

            // 5. 元数据推送：尽早把检索到的引用文献等元数据推给前端，让前端无需等待大模型生成就能先展示参考资料
            QaStreamMetadataDTO metadata = QaStreamMetadataDTO.builder()
                    .sessionId(sessionId)
                    .messageId(messageId)
                    .qaTraceId(qaTraceId)
                    .standaloneQuestionZh(rewrite.standaloneQuestionZh())
                    .queryEn(rewrite.queryEn())
                    .citations(citations)
                    .build();
            sender.accept(QaWebSocketResponse.metadata(metadata));

            // 6. 构建上下文：获取包含最近 N 轮对话记录的 ChatMemory
            ChatMemory memory = chatMemoryProvider.get(sessionId);
            // 把用户的真实提问和刚刚检索到的内容拼接在一起，生成带有约束条件的提示词
            String groundedUserMessage = buildGroundedUserMessage(request.getMessage(), evidence);
            
            // 组装最终送给大模型的消息列表：System Prompt -> 历史聊天记录 -> 本次带有证据的提问
            List<ChatMessage> messages = new ArrayList<>();
            messages.add(SystemMessage.from(systemPrompt()));
            messages.addAll(memory.messages());
            messages.add(UserMessage.from(groundedUserMessage));

            // 缓存整个回答内容，以便在流式输出结束后存入历史上下文
            StringBuilder answerBuffer = new StringBuilder();
            // 7. 发起大模型流式调用
            streamingChatModel.chat(messages, new StreamingChatResponseHandler() {
                @Override
                public void onPartialResponse(String token) {
                    // 每当大模型生成一个词汇 (Token) 时，将其追加到缓存，并立刻触发 sender 推送 'token' 消息给前端
                    answerBuffer.append(token);
                    sender.accept(QaWebSocketResponse.token(token));
                }

                @Override
                public void onCompleteResponse(ChatResponse response) {
                    // 当大模型生成完毕后，将用户原始的干净提问（非带证据的那一长串）加入历史记忆
                    memory.add(UserMessage.from(request.getMessage()));
                    // 将 AI 最终拼成的完整回复也加入历史记忆
                    AiMessage aiMessage = response.aiMessage() == null
                            ? AiMessage.from(answerBuffer.toString())
                            : response.aiMessage();
                    memory.add(aiMessage);
                    // 向前端推送 'completion' 事件，代表流式回答彻底结束
                    sender.accept(QaWebSocketResponse.completion(metadata));
                }

                @Override
                public void onError(Throwable error) {
                    // 在流式生成过程中发生异常（如 API Key 失效、网络断开等），推送错误消息
                    log.error("QA stream failed, qaTraceId={}", qaTraceId, error);
                    sender.accept(QaWebSocketResponse.error(error.getMessage()));
                }
            });
        } catch (Exception exception) {
            // 在前置阶段（如重写、Elasticsearch 检索、参数拼接等）发生异常，直接兜底推送错误消息
            log.error("QA stream preparation failed, qaTraceId={}", qaTraceId, exception);
            sender.accept(QaWebSocketResponse.error(exception.getMessage()));
        }
    }

    /**
     * 构建“基于证据的”用户提问（Grounded Generation）。
     * <p>
     * 这是控制 LLM 幻觉的核心，从 QaProperties 中读取预设的提示词模板，
     * 强制要求其在给定的检索结果片段中寻找答案并打上引用标签。
     * </p>
     */
    private String buildGroundedUserMessage(String userMessage, String evidence) {
        return qaProperties.getPrompts().getGroundedUserMessage()
                .replace("{message}", userMessage == null ? "" : userMessage)
                .replace("{evidence}", evidence == null ? "" : evidence);
    }

    /**
     * 获取系统提示词（System Prompt），用以树立机器人的底层行为准则。
     */
    private String systemPrompt() {
        return qaProperties.getPrompts().getSystemPrompt();
    }

    /**
     * 确保会话 ID 存在，如果前端未传递则在后端生成一个新的 UUID。
     */
    private String ensureSessionId(String sessionId) {
        return StringUtils.hasText(sessionId) ? sessionId : UUID.randomUUID().toString();
    }
}
