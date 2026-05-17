package com.kesf.backend.service.impl;

import java.util.List;

/**
 * 论文内容块（不可变记录）。
 * <p>
 * 由 {@link PaperChunkBuildService} 从 MinerU 的 content_list_v2.json 解析生成。
 * 每个 PaperChunk 代表论文中一个可检索的文本单元（段落/标题/图表/公式等），
 * 包含原始文本、上下文增强文本、位置信息及内容哈希。
 * </p>
 *
 * @param chunkIndex     内容块在论文中的序号（从 0 开始）
 * @param chunkType      标准化后的内容块类型（title/paragraph/figure/table/equation）
 * @param sectionTitle   所属章节标题
 * @param sectionPath    完整章节路径（如 "Introduction / Methods / Results"）
 * @param pageStart      起始页码
 * @param pageEnd        结束页码
 * @param bbox           页面中的边界框坐标 [x0, y0, x1, y1]
 * @param rawText        原始提取文本（仅该块本身的内容）
 * @param contextText    上下文增强文本（嵌入论文元数据 + 章节路径 + 原始文本，用于向量化）
 * @param contentHash    内容 SHA-256 哈希（paperMd5 + rawText），用于去重
 * @param sourceObjectKey MinerU 解析产物在 MinIO 中的对象键，用于追溯
 */
public record PaperChunk(
        int chunkIndex,
        String chunkType,
        String sectionTitle,
        String sectionPath,
        int pageStart,
        int pageEnd,
        List<Integer> bbox,
        String rawText,
        String contextText,
        String contentHash,
        String sourceObjectKey
) {
}
