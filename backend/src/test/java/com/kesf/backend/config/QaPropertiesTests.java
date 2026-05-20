package com.kesf.backend.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.util.Map;

import static java.util.Map.entry;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link QaProperties} 配置属性绑定的单元测试。
 *
 * 该测试不启动完整的 Spring 容器，而是直接使用 Spring Boot 的 {@link Binder} API
 * 来模拟配置属性绑定过程，验证：
 * <ul>
 *   <li>所有检索相关参数能否正确从 {@code scholarease.qa.*} 配置键映射到 Java 字段。</li>
 *   <li>嵌套的聊天模型配置（Chat 子类）能否正确绑定。</li>
 *   <li>{@link QaProperties.Chat#resolveMaxTokens()} 是否能根据模型名称正确解析 Token 上限。</li>
 * </ul>
 *
 * 使用 {@link MapPropertySource} 模拟 YAML 中的配置键值对，
 * 避免依赖真实的 application.yml 文件，保证测试的独立性和确定性。
 */
class QaPropertiesTests {

    /**
     * 验证所有 QA 检索参数和聊天模型配置的正确绑定。
     *
     * 测试流程：
     * <ol>
     *   <li>创建一个 {@link StandardEnvironment} 并注入模拟的配置键值对。</li>
     *   <li>通过 {@link Binder} 将键值对绑定到 {@link QaProperties} 实例。</li>
     *   <li>逐一断言每个字段的值是否与注入的配置一致。</li>
     * </ol>
     *
     * 注意：配置值故意设置为与默认值不同的数字，以验证绑定确实生效而非回退到默认值。
     */
    @Test
    void bindsQaRetrievalDefaults() {
        // 1. 构建模拟的配置环境，注入 scholarease.qa.* 下的所有配置项
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("test", Map.ofEntries(
                entry("scholarease.qa.history-recent-turns", "12"),
                entry("scholarease.qa.redis-session-ttl-days", "14"),
                entry("scholarease.qa.vector-top-k", "30"),
                entry("scholarease.qa.vector-num-candidates", "180"),
                entry("scholarease.qa.bm25-top-k", "31"),
                entry("scholarease.qa.rrf-k", "55"),
                entry("scholarease.qa.merged-top-k", "50"),
                entry("scholarease.qa.rerank-input-top-k", "45"),
                entry("scholarease.qa.rerank-output-top-k", "9"),
                entry("scholarease.qa.final-context-min-chunks", "3"),
                entry("scholarease.qa.final-context-max-chunks", "7"),
                entry("scholarease.qa.minimum-evidence-score", "0.025"),
                entry("scholarease.qa.minimum-evidence-chunks", "2"),
                entry("scholarease.qa.chat.base-url", "https://api.deepseek.com"),
                entry("scholarease.qa.chat.api-key", "sk-test"),
                entry("scholarease.qa.chat.model-name", "deepseek-v4-pro"),
                entry("scholarease.qa.chat.temperature", "0.3"),
                entry("scholarease.qa.chat.top-p", "0.8"),
                entry("scholarease.qa.chat.timeout-seconds", "50"),
                entry("scholarease.qa.chat.model-max-tokens.deepseek-v4-pro", "8192"),
                entry("scholarease.qa.chat.model-max-tokens.deepseek-v4-flash", "4096"),
                entry("scholarease.qa.prompts.rewrite-prompt", "rewrite {message}"),
                entry("scholarease.qa.prompts.grounded-user-message", "grounded {message} {evidence}"),
                entry("scholarease.qa.prompts.system-prompt", "system")
        )));

        // 2. 使用 Binder 将配置绑定到 QaProperties 对象
        Binder binder = new Binder(ConfigurationPropertySources.get(environment));
        QaProperties properties = binder.bind("scholarease.qa", Bindable.of(QaProperties.class)).get();

        // 3. 验证检索参数绑定正确
        assertThat(properties.getHistoryRecentTurns()).isEqualTo(12);
        assertThat(properties.getRedisSessionTtlDays()).isEqualTo(14);
        assertThat(properties.getVectorTopK()).isEqualTo(30);
        assertThat(properties.getVectorNumCandidates()).isEqualTo(180);
        assertThat(properties.getBm25TopK()).isEqualTo(31);
        assertThat(properties.getRrfK()).isEqualTo(55);
        assertThat(properties.getMergedTopK()).isEqualTo(50);
        assertThat(properties.getRerankInputTopK()).isEqualTo(45);
        assertThat(properties.getRerankOutputTopK()).isEqualTo(9);
        assertThat(properties.getFinalContextMinChunks()).isEqualTo(3);
        assertThat(properties.getFinalContextMaxChunks()).isEqualTo(7);
        assertThat(properties.getMinimumEvidenceScore()).isEqualTo(0.025d);
        assertThat(properties.getMinimumEvidenceChunks()).isEqualTo(2);

        // 4. 验证聊天模型配置绑定正确
        assertThat(properties.getChat().getBaseUrl()).isEqualTo("https://api.deepseek.com");
        assertThat(properties.getChat().getApiKey()).isEqualTo("sk-test");
        assertThat(properties.getChat().getModelName()).isEqualTo("deepseek-v4-pro");
        assertThat(properties.getChat().getTemperature()).isEqualTo(0.3);
        assertThat(properties.getChat().getTopP()).isEqualTo(0.8);
        assertThat(properties.getChat().getTimeoutSeconds()).isEqualTo(50);
        assertThat(properties.getChat().resolveMaxTokens()).isEqualTo(8192);
        assertThat(properties.getChat().getModelMaxTokens())
                .containsEntry("deepseek-v4-flash", 4096);

        // 5. 验证提示词模板配置绑定正确
        assertThat(properties.getPrompts().getRewritePrompt()).isEqualTo("rewrite {message}");
        assertThat(properties.getPrompts().getGroundedUserMessage()).isEqualTo("grounded {message} {evidence}");
        assertThat(properties.getPrompts().getSystemPrompt()).isEqualTo("system");
    }

    @Test
    void answerPromptsDoNotContainNoEvidenceBranch() {
        QaProperties properties = new QaProperties();

        assertThat(properties.getPrompts().getGroundedUserMessage())
                .doesNotContain("证据不足")
                .doesNotContain("暂时无法回答")
                .doesNotContain("没有检索到");
        assertThat(properties.getPrompts().getSystemPrompt())
                .doesNotContain("证据不足")
                .doesNotContain("暂时无法回答")
                .doesNotContain("没有检索到");
        assertThat(properties.getPrompts().getNoEvidenceMessage())
                .contains("暂时无法回答");
    }
}
