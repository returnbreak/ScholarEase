package com.kesf.backend.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * ScholarEase 问答（QA）功能的配置属性类。
 *
 * 该类通过 Spring Boot 的 {@link ConfigurationProperties} 机制，
 * 将 application.yml 中 {@code scholarease.qa} 前缀下的配置项自动绑定到对应的 Java 字段上。
 *
 * 主要涵盖三个方面的配置：
 * <ul>
 *   <li><b>检索参数</b>：向量检索、BM25 检索、RRF 融合、Rerank 重排序以及最终上下文窗口的大小控制。</li>
 *   <li><b>会话参数</b>：历史对话轮次、Redis 会话过期时间。</li>
 *   <li><b>对话模型参数</b>：LLM 提供商、适配器、接口地址、模型名称及最大 Token 数。</li>
 * </ul>
 */
@Data
@Component
@ConfigurationProperties(prefix = "scholarease.qa")
public class QaProperties {

    /**
     * 是否启用 QA 功能的总开关。
     * 默认为 true，设为 false 时可整体关闭问答服务。
     */
    private boolean enabled = true;

    /**
     * 对话历史保留的最近轮次数。
     * 每轮包含一次用户提问和一次助手回答，用于构建多轮对话的上下文窗口。
     * 默认保留最近 20 轮。
     */
    private int historyRecentTurns = 20;

    /**
     * Redis 中会话数据的 TTL（存活时间），单位为天。
     * 超过该时间后，会话数据将被自动清除。
     * 默认 30 天。
     */
    private int redisSessionTtlDays = 30;

    /**
     * 向量检索返回的 Top-K 结果数。
     * 即在向量相似度搜索中，返回与查询向量最相似的前 K 个文档块。
     * 默认值为 40。
     */
    private int vectorTopK = 40;

    /**
     * 向量检索的候选集大小（numCandidates）。
     * Elasticsearch 在进行 KNN 搜索时，会先从每个分片中选取 numCandidates 个候选者，
     * 再从中选出最终的 Top-K 结果。增大该值可提高召回率，但会降低性能。
     * 默认值为 200。
     */
    private int vectorNumCandidates = 200;

    /**
     * BM25 关键词检索返回的 Top-K 结果数。
     * BM25 是一种基于词频统计的传统检索算法，与向量检索互补，
     * 混合使用可兼顾语义相关性和关键词匹配精度。
     * 默认值为 40。
     */
    private int bm25TopK = 40;

    /**
     * RRF（Reciprocal Rank Fusion，倒数排名融合）算法的参数 k。
     * RRF 用于将向量检索和 BM25 检索的结果列表融合为一个统一的相关性排序。
     * k 值决定了排名对最终分数的影响程度：较小的 k 使高排名结果权重更大。
     * 默认值为 60。
     */
    private int rrfK = 60;

    /**
     * RRF 融合后保留的 Top-K 结果数。
     * 对向量检索和 BM25 检索的结果进行 RRF 融合后，取前 K 个结果进入下一步处理。
     * 默认值为 60。
     */
    private int mergedTopK = 60;

    /**
     * Rerank（重排序）阶段的输入 Top-K 结果数。
     * 将 RRF 融合后的前 K 个结果送入 Rerank 模型进行更精细的语义重排序。
     * 默认值为 60。
     */
    private int rerankInputTopK = 60;

    /**
     * Rerank（重排序）阶段的输出 Top-K 结果数。
     * Rerank 模型对输入结果重新打分排序后，保留前 K 个最相关的文档块。
     * 默认值为 10。
     */
    private int rerankOutputTopK = 10;

    /**
     * 最终上下文中保留的最小文档块数量。
     * 如果 Rerank 后结果不足此数量，则在最终提示词中至少保留此数量的文档块作为上下文。
     * 默认值为 4。
     */
    private int finalContextMinChunks = 4;

    /**
     * 最终上下文中保留的最大文档块数量。
     * 防止上下文过长导致超出 LLM 的 Token 限制，将文档块数量控制在此值以内。
     * 默认值为 8。
     */
    private int finalContextMaxChunks = 8;

    /**
     * 最终进入回答模型的证据最低可信分数。
     *
     * <p>当前检索主链路会把向量召回和 BM25 召回通过 RRF 融合，RRF 分数通常约为：
     * 单路 rank1 = 1 / (60 + 1) ≈ 0.016，双路 rank1 = 0.032。
     * 默认值 0.02 会过滤掉仅靠单路弱召回得到的候选，避免“没有足够证据”时仍组织答案。</p>
     */
    private double minimumEvidenceScore = 0.02d;

    /**
     * 低于该数量的可信证据不进入回答模型，直接返回无证据提示。
     */
    private int minimumEvidenceChunks = 1;

    /**
     * QA 链路使用的提示词模板。
     *
     * <p>通过配置文件维护，便于调整 query rewrite、证据约束和系统行为边界。</p>
     */
    private Prompts prompts = new Prompts();

    /**
     * 对话模型（Chat Model）的相关配置。
     * 包含 LLM 提供商、适配器类型、API 地址、模型名称及 Token 限制等。
     */
    private Chat chat = new Chat();

    @Data
    public static class Prompts {

        private String rewritePrompt = """
                你是 ScholarEase 的论文检索 query rewrite 模块。
                用户会用中文询问英文论文内容。请把问题改写为适合 Elasticsearch BM25 检索的英文 query。
                要求：
                1. 保留模型名、方法名、数据集名、指标名、公式符号等专有名词。
                2. 不要引入用户问题中不存在的论文事实。
                3. 只输出 JSON，不要 Markdown，不要解释。

                JSON schema:
                {
                  "standaloneQuestionZh": "中文独立问题",
                  "queryEn": "English retrieval query",
                  "bm25Keywords": ["keyword"],
                  "exactTerms": ["exact term"]
                }

                用户问题：
                {message}
                """;

        private String groundedUserMessage = """
                用户问题：
                {message}

                检索证据：
                {evidence}

                请只依据上述证据用中文回答。每个关键结论后标注最终回答引用编号，例如 [1]、[2]。
                最终回答引用编号必须从 [1] 开始，按正文第一次引用顺序连续编号，禁止出现 [1][3][8] 这种跳号。
                即使使用的是检索证据中的第 3、第 8 条，也必须在最终回答中重新编号为连续的 [1]、[2]。
                回答最后必须追加 “## 参考文献” 小节，并按最终回答引用编号列出参考文献：
                [1] 文献名称 · 对应页数
                参考文献条目必须来自检索证据中的“检索证据引用候选”清单或 Reference 字段，不要自己编造文献名称或页码。
                每个参考文献条目必须单独占一行，禁止把 [1]、[2] 等多个条目合并到同一行。
                回答必须使用 Markdown 格式输出，以便前端正确渲染标题、列表、表格、公式和代码块。
                """;

        private String noEvidenceMessage = """
                当前没有检索到与您问题相关的论文证据，暂时无法回答该问题。
                建议您检查文献库是否已导入相关论文，或尝试调整问题表述后重新提问。
                """;

        private String systemPrompt = """
                你是 ScholarEase 的论文问答助手。
                只能依据给定 evidence 回答，不允许编造论文事实。
                使用中文回答。
                使用 Markdown 格式组织回答，必要时使用标题、列表、表格、公式或代码块。
                每个关键结论后必须使用最终回答引用编号，例如 [1]、[2]。
                最终回答引用编号必须连续，不允许跳号；不要把检索证据编号直接当作最终引用编号。
                回答最后必须输出 “## 参考文献” 小节，按最终回答引用编号列出被引用证据的文献名称和对应页数。
                参考文献小节中每个 [数字] 条目必须单独换行。
                不要输出没有来源的论文结论。
                """;
    }

    /**
     * 对话模型的子配置类。
     *
     * 支持通过 YAML 配置灵活切换不同的 LLM 提供商和模型，
     * 并可根据模型名称自动解析对应的最大 Token 数。
     */
    @Data
    public static class Chat {

        /**
         * LLM API 的基础 URL 地址。
         * 默认为 DeepSeek 的官方 API 地址。
         */
        private String baseUrl = "https://api.deepseek.com";

        /**
         * LLM API Key。优先通过环境变量注入，前端未填写 apiKey 时使用它作为兜底。
         */
        private String apiKey = "";

        /**
         * 当前使用的具体模型名称。
         * 默认为 "deepseek-v4-pro"，即 DeepSeek 的旗舰推理模型。
         */
        private String modelName = "deepseek-v4-pro";

        private Double temperature = 0.2;

        private Double topP = 0.9;

        private Integer timeoutSeconds = 60;

        /**
         * 各模型与其最大输出 Token 数的映射关系。
         * key 为模型名称，value 为该模型单次请求允许的最大生成 Token 数。
         * 使用 LinkedHashMap 以保证插入顺序，便于配置阅读和调试。
         *
         * 预置了两种 DeepSeek 模型：
         * <ul>
         *   <li>deepseek-v4-pro：8192 tokens（旗舰模型，适用于复杂推理）</li>
         *   <li>deepseek-v4-flash：4096 tokens（轻量模型，适用于快速响应）</li>
         * </ul>
         */
        private Map<String, Integer> modelMaxTokens = new LinkedHashMap<>(Map.of(
                "deepseek-v4-pro", 8192,
                "deepseek-v4-flash", 4096
        ));

        /**
         * 根据当前配置的 {@code modelName} 解析对应的最大 Token 数。
         *
         * 查找逻辑：从 {@link #modelMaxTokens} 映射中查找当前模型名称对应的 Token 上限；
         * 若未找到，则回退到默认值 4096，保证在任何情况下都有一个合理的 Token 限制。
         *
         * @return 当前模型的最大输出 Token 数
         */
        public int resolveMaxTokens() {
            return modelMaxTokens.getOrDefault(modelName, 4096);
        }
    }
}
