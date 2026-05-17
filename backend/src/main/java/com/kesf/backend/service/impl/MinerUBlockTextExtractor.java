package com.kesf.backend.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * MinerU 内容块文本提取器。
 * <p>
 * 负责从 MinerU 解析产物的 JSON 块中提取可读文本。根据块类型（title/paragraph/image/table/equation）
 * 采用不同的提取策略：
 * </p>
 * <ul>
 *   <li>title → 提取标题内容</li>
 *   <li>paragraph → 提取段落内容（含行内公式 LaTeX）</li>
 *   <li>image → 提取图片标题 + 路径 + 脚注</li>
 *   <li>table → 提取表格标题 + HTML 源码</li>
 *   <li>equation_interline → 提取公式 LaTeX + 图片路径</li>
 * </ul>
 * <p>
 * 所有的文本输出都经过 {@link #normalize(String)} 处理：合并空白、去重空行。
 * </p>
 */
@Component
public class MinerUBlockTextExtractor {

    /**
     * 按块类型提取文本。
     *
     * @param blockType MinerU 块类型（如 "title", "paragraph", "image", "table", "equation_interline"）
     * @param content   块内容 JSON 节点
     * @return 提取并规范化后的文本，无内容时返回空字符串
     */
    public String extract(String blockType, JsonNode content) {
        if (content == null || content.isNull()) {
            return "";
        }
        return switch (normalizeType(blockType)) {
            case "title"             -> normalize(joinContentNodes(content.get("title_content")));
            case "paragraph"         -> normalize(joinContentNodes(content.get("paragraph_content")));
            case "image"             -> normalize(extractImage(content));
            case "table"             -> normalize(extractTable(content));
            case "equation_interline" -> normalize(extractEquation(content));
            default                  -> normalize(joinContentNodes(content));
        };
    }

    /**
     * 提取图片块文本：标题 + 图片路径 + 脚注。
     * 三者之间用换行分隔，缺失的部分跳过。
     */
    private String extractImage(JsonNode content) {
        List<String> parts = new ArrayList<>();
        String caption = joinContentNodes(content.get("image_caption"));
        if (StringUtils.hasText(caption)) {
            parts.add("Figure: " + caption);
        }
        JsonNode imagePath = content.at("/image_source/path");
        if (imagePath.isTextual() && StringUtils.hasText(imagePath.asText())) {
            parts.add("Image Path: " + imagePath.asText());
        }
        String footnote = joinContentNodes(content.get("image_footnote"));
        if (StringUtils.hasText(footnote)) {
            parts.add("Footnote: " + footnote);
        }
        return String.join("\n", parts);
    }

    /**
     * 提取表格块文本：表格标题 + HTML 源码。
     */
    private String extractTable(JsonNode content) {
        List<String> parts = new ArrayList<>();
        String caption = joinContentNodes(content.get("table_caption"));
        if (StringUtils.hasText(caption)) {
            parts.add("Table: " + caption);
        }
        JsonNode html = content.get("html");
        if (html != null && html.isTextual() && StringUtils.hasText(html.asText())) {
            parts.add("HTML:\n" + html.asText());
        }
        return String.join("\n", parts);
    }

    /**
     * 提取行间公式块文本：LaTeX 公式 + 图片路径。
     */
    private String extractEquation(JsonNode content) {
        List<String> parts = new ArrayList<>();
        JsonNode mathContent = content.get("math_content");
        if (mathContent != null && mathContent.isTextual() && StringUtils.hasText(mathContent.asText())) {
            parts.add("Equation: " + mathContent.asText());
        }
        JsonNode imagePath = content.at("/image_source/path");
        if (imagePath.isTextual() && StringUtils.hasText(imagePath.asText())) {
            parts.add("Image Path: " + imagePath.asText());
        }
        return String.join("\n", parts);
    }

    /**
     * 递归拼接内容节点树中的所有文本。
     * <p>
     * 对于行内公式（equation_inline），自动包裹 {@code $...$} 标记，
     * 以保留 LaTeX 数学公式的语义。
     * </p>
     */
    private String joinContentNodes(JsonNode node) {
        if (node == null || node.isNull()) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        appendContent(parts, node);
        return String.join("", parts);
    }

    /** 递归遍历 JSON 树，收集所有文本片段并拼接 */
    private void appendContent(List<String> parts, JsonNode node) {
        if (node == null || node.isNull()) {
            return;
        }
        if (node.isArray()) {
            // 数组节点 → 遍历子节点
            node.forEach(child -> appendContent(parts, child));
            return;
        }
        if (node.isObject()) {
            String type = normalizeType(textValue(node.get("type")));
            JsonNode content = node.get("content");
            // 行内公式特殊处理：包裹 $...$ 标记
            if ("equation_inline".equals(type) && content != null && content.isTextual()) {
                parts.add("$" + content.asText() + "$");
                return;
            }
            if (content != null) {
                appendContent(parts, content);
            }
            return;
        }
        if (node.isTextual()) {
            parts.add(node.asText());
        }
    }

    /** 安全获取文本节点值 */
    private String textValue(JsonNode node) {
        return node != null && node.isTextual() ? node.asText() : "";
    }

    /** 类型标准化：去除首尾空白并转为小写 */
    private String normalizeType(String type) {
        return type == null ? "" : type.trim().toLowerCase();
    }

    /**
     * 文本规范化：合并连续空白字符，去除首尾空格，合并空行。
     * <p>
     * 同时处理 Windows 风格的 {@code \r\n} 换行符。
     * </p>
     */
    private String normalize(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (String line : value.replace('\r', '\n').split("\n")) {
            String normalizedLine = line.replaceAll("\\s+", " ").trim(); // 合并行内空白
            if (StringUtils.hasText(normalizedLine)) {
                if (!builder.isEmpty()) {
                    builder.append('\n');
                }
                builder.append(normalizedLine);
            }
        }
        return builder.toString();
    }
}
