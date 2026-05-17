package com.kesf.backend.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kesf.backend.kafka.PaperVectorIndexTask;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

/**
 * 论文内容块构建服务。
 * <p>
 * 解析 MinerU 输出的 content_list_v2.json，将论文按页面和逻辑块拆分为多个可检索的
 * {@link PaperChunk}。每个 chunk 包含：
 * </p>
 * <ul>
 *   <li>原始提取文本（用于展示）</li>
 *   <li>上下文增强文本（嵌入论文元数据 + 章节路径，用于向量化和语义搜索）</li>
 *   <li>内容哈希（用于去重）</li>
 *   <li>位置信息（页码、边界框坐标）</li>
 * </ul>
 * <p>
 * 内置过滤机制：跳过页眉（page_header）、页脚（page_footer）和页码（page_number）等无检索价值的块。
 * </p>
 */
@Service
@RequiredArgsConstructor
public class PaperChunkBuildService {

    /** 需跳过的 MinerU 块类型：页眉、页脚、页码等无检索价值的内容 */
    private static final Set<String> SKIPPED_TYPES = Set.of("page_header", "page_footer", "page_number");

    private final ObjectMapper objectMapper;
    private final MinerUBlockTextExtractor textExtractor;

    /**
     * 从 MinerU content_list_v2.json 的字节内容构建内容块列表。
     *
     * @param task             论文向量索引任务（用于获取元数据和路径信息）
     * @param contentListBytes MinerU 输出文件（content_list_v2.json）的原始字节
     * @return 提取到的所有内容块，按页面和块顺序排列
     */
    public List<PaperChunk> buildChunks(PaperVectorIndexTask task, byte[] contentListBytes) {
        JsonNode pages = readPages(contentListBytes); // 解析 JSON：外层数组，每个元素是一页的块列表
        List<PaperChunk> chunks = new ArrayList<>();
        List<Section> sections = new ArrayList<>(); // 当前累积的章节层级栈
        int chunkIndex = 0;

        for (int pageIndex = 0; pageIndex < pages.size(); pageIndex++) {
            JsonNode pageBlocks = pages.get(pageIndex);
            if (pageBlocks == null || !pageBlocks.isArray()) {
                continue;
            }
            int pageNo = pageIndex + 1; // 页码从 1 开始
            for (JsonNode block : pageBlocks) {
                String type = normalizeType(text(block.get("type")));
                // 跳过无法识别的类型和无检索价值的块（页眉/页脚/页码）
                if (!StringUtils.hasText(type) || SKIPPED_TYPES.contains(type)) {
                    continue;
                }

                String rawText = textExtractor.extract(type, block.get("content"));
                if (!StringUtils.hasText(rawText)) {
                    continue; // 跳过无法提取到有效文本的块
                }

                // 遇到标题时更新章节层级栈
                if ("title".equals(type)) {
                    updateSections(sections, block.at("/content/level").asInt(1), rawText);
                }

                // 当前所属章节标题（栈顶元素）
                String sectionTitle = sections.isEmpty() ? "" : sections.get(sections.size() - 1).title();
                // 完整章节路径："Abstract / Introduction / Background"
                String sectionPath = sections.stream()
                        .map(Section::title)
                        .filter(StringUtils::hasText)
                        .reduce((left, right) -> left + " / " + right)
                        .orElse("");
                String chunkType = toChunkType(type); // MinerU 类型 → 标准化 chunk 类型
                chunks.add(new PaperChunk(
                        chunkIndex++,
                        chunkType,
                        sectionTitle,
                        sectionPath,
                        pageNo,
                        pageNo,
                        readBbox(block.get("bbox")), // 边界框坐标 [x0, y0, x1, y1]
                        rawText,
                        buildContextText(task, sectionPath, pageNo, chunkType, rawText), // 含元数据的增强文本
                        sha256(task.getPaperMd5() + "\n" + rawText), // 内容哈希：paperMd5 + 文本
                        task.getContentListObjectKey()
                ));
            }
        }
        return chunks;
    }

    /**
     * 解析 MinerU content_list_v2.json。
     * <p>
     * 文件结构为 JSON 数组，每个元素代表一页，页内为块列表。
     * </p>
     */
    private JsonNode readPages(byte[] contentListBytes) {
        try {
            JsonNode root = objectMapper.readTree(contentListBytes);
            if (!root.isArray()) {
                throw new IllegalArgumentException("MinerU content_list_v2.json root must be an array");
            }
            return root;
        } catch (IOException exception) {
            throw new IllegalArgumentException("Failed to parse MinerU content_list_v2.json", exception);
        }
    }

    /**
     * 更新章节层级栈。
     * <p>
     * 遇到新标题时，移除所有 ≥ 当前层级的旧标题，然后压入新标题。
     * 例如：栈中有 [1. Introduction, 2. Methods] → 遇到 level=1 的 "Results" →
     * 移除 2. Methods（level≥1），压入 Results → [1. Introduction, 1. Results]。
     * </p>
     */
    private void updateSections(List<Section> sections, int level, String title) {
        int safeLevel = Math.max(1, level);
        sections.removeIf(section -> section.level() >= safeLevel);
        sections.add(new Section(safeLevel, title));
    }

    /**
     * 构建上下文增强文本。
     * <p>
     * 在原始文本前添加论文元数据标签（标题、作者、年份、期刊/会议、DOI、章节、页码、类型），
     * 使向量化后的语义搜索能利用这些结构化信息提升相关性。
     * </p>
     */
    private String buildContextText(
            PaperVectorIndexTask task,
            String sectionPath,
            int pageNo,
            String chunkType,
            String rawText
    ) {
        List<String> lines = new ArrayList<>();
        addLine(lines, "Paper", task.getTitle());
        addLine(lines, "Authors", String.join(", ",
                task.getAuthors() == null ? List.of() : task.getAuthors()));
        addLine(lines, "Year", task.getYear() == null ? "" : String.valueOf(task.getYear()));
        addLine(lines, "Venue", task.getVenue());
        addLine(lines, "DOI", task.getDoi());
        addLine(lines, "Section", sectionPath);
        addLine(lines, "Page", String.valueOf(pageNo));
        addLine(lines, "Type", chunkType);
        lines.add(""); // 空行分隔元数据与正文
        lines.add(rawText);
        return String.join("\n", lines);
    }

    /** 仅当 value 非空时添加 "label: value" 行 */
    private void addLine(List<String> lines, String label, String value) {
        if (StringUtils.hasText(value)) {
            lines.add(label + ": " + value);
        }
    }

    /** 解析边界框坐标 JSON 数组为 List&lt;Integer&gt; */
    private List<Integer> readBbox(JsonNode bbox) {
        if (bbox == null || !bbox.isArray()) {
            return List.of();
        }
        List<Integer> values = new ArrayList<>();
        bbox.forEach(item -> values.add(item.asInt()));
        return values;
    }

    /** MinerU 内部类型 → 标准化 chunk 类型（image → figure, equation_interline → equation） */
    private String toChunkType(String minerUType) {
        return switch (minerUType) {
            case "image"              -> "figure";
            case "equation_interline" -> "equation";
            default                   -> minerUType;
        };
    }

    private String normalizeType(String type) {
        return type == null ? "" : type.trim().toLowerCase();
    }

    /** 安全获取 JSON 文本节点值 */
    private String text(JsonNode node) {
        return node != null && node.isTextual() ? node.asText() : "";
    }

    /** 计算 SHA-256 哈希（用于内容去重） */
    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 digest is unavailable", exception);
        }
    }

    /** 内部记录：章节（层级 + 标题），用于构建章节路径栈 */
    private record Section(int level, String title) {
    }
}
