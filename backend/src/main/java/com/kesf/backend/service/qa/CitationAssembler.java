package com.kesf.backend.service.qa;

import com.kesf.backend.dto.qa.QaCitationDTO;
import com.kesf.backend.service.PaperRetrievalSearchService;
import com.kesf.backend.service.impl.PaperVectorDocument;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 引用（证据）组装器。
 * <p>
 * 负责将底层检索（Elasticsearch）召回的文档分块（Chunk），
 * 转换为前端展示用的引用对象（QaCitationDTO），以及拼接成喂给大模型的上下文提示词（Evidence Prompt）。
 * </p>
 */
@Service
public class CitationAssembler {

    /**
     * 将底层混合检索的结果列表，转换为传输给前端的引用数据列表。
     *
     * @param hits 检索命中的文档结果列表
     * @return 带有标号的引用数据列表
     */
    public List<QaCitationDTO> toCitations(List<PaperRetrievalSearchService.PaperRetrievalHit> hits) {
        // 线程安全的计数器，用于生成从 1 开始的自增序号
        AtomicInteger index = new AtomicInteger(1);
        return hits.stream()
                // 为每个片段生成形如 "S1", "S2", "S3" 的引用 ID，大模型将使用这些 ID 进行标注
                .map(hit -> toCitation("S" + index.getAndIncrement(), hit))
                .toList();
    }

    /**
     * 将引用数据列表组装成给大模型阅读的纯文本提示词（Prompt）。
     * 结构化地展示每个片段的出处（标题、章节、页码）以及具体的原文内容。
     */
    public String toEvidencePrompt(List<QaCitationDTO> citations) {
        StringBuilder builder = new StringBuilder();
        for (QaCitationDTO citation : citations) {
            // 拼接格式示例：
            // [S1]
            // Paper: Attention Is All You Need
            // Section: 3.1 Architecture
            // Page: 2-3
            // Chunk: 9f86d081884c7d659a2feaa0c55ad015#4
            // Text:
            // (具体的论文段落内容...)
            builder.append("[")
                    .append(citation.getCitationId())
                    .append("]\n")
                    .append("Paper: ")
                    .append(nullToEmpty(citation.getTitle()))
                    .append("\nSection: ")
                    .append(nullToEmpty(citation.getSectionPath()))
                    .append("\nPage: ")
                    .append(citation.getPageStart() == null ? "" : citation.getPageStart())
                    .append(citation.getPageEnd() == null ? "" : "-" + citation.getPageEnd())
                    .append("\nChunk: ")
                    .append(nullToEmpty(citation.getPaperMd5()))
                    .append("#")
                    .append(citation.getChunkIndex() == null ? "" : citation.getChunkIndex())
                    .append("\nText:\n")
                    .append(nullToEmpty(citation.getRawTextPreview()))
                    .append("\n\n");
        }
        return builder.toString();
    }

    /**
     * 将单条检索命中结果转换为 QaCitationDTO 业务对象。
     */
    private QaCitationDTO toCitation(String citationId, PaperRetrievalSearchService.PaperRetrievalHit hit) {
        PaperVectorDocument document = hit.document();
        return QaCitationDTO.builder()
                .citationId(citationId)
                .paperMd5(document.getPaperMd5())
                .chunkIndex(document.getChunkIndex())
                .title(document.getTitle())
                .sectionPath(document.getSectionPath())
                .pageStart(document.getPageStart())
                .pageEnd(document.getPageEnd())
                // 对超长的原文片段进行截断，防止塞入太多内容导致大模型 Token 溢出
                .rawTextPreview(preview(document.getRawText()))
                // 如果经过了 RRF 重打分，则优先展示 RRF 分数；否则展示 ES 原生分数
                .score(hit.rrfScore() != null && hit.rrfScore() > 0 ? hit.rrfScore() : hit.score())
                .build();
    }

    /**
     * 文本截断预览逻辑。
     * 限制单个 Chunk 喂给大模型的文本长度，最大保留 1200 个字符。
     */
    private String preview(String rawText) {
        if (rawText == null) {
            return "";
        }
        return rawText.length() <= 1200 ? rawText : rawText.substring(0, 1200);
    }

    /**
     * 辅助方法：将 null 字符串安全地转换为空字符串。
     */
    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
