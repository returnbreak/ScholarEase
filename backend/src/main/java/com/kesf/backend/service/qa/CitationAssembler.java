package com.kesf.backend.service.qa;

import com.kesf.backend.dto.qa.QaCitationDTO;
import com.kesf.backend.service.PaperRetrievalSearchService;
import com.kesf.backend.service.impl.PaperVectorDocument;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class CitationAssembler {

    public List<QaCitationDTO> toCitations(List<PaperRetrievalSearchService.PaperRetrievalHit> hits) {
        AtomicInteger index = new AtomicInteger(1);
        return hits.stream()
                .map(hit -> toCitation("S" + index.getAndIncrement(), hit))
                .toList();
    }

    public String toEvidencePrompt(List<QaCitationDTO> citations) {
        StringBuilder builder = new StringBuilder();
        for (QaCitationDTO citation : citations) {
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
