package com.kesf.backend.service.impl;

import com.kesf.backend.kafka.PaperVectorIndexTask;

import java.util.ArrayList;
import java.util.List;

import lombok.Data;

/**
 * Elasticsearch 中的论文向量文档——对应 scholarease_base 索引的一条记录。
 * <p>
 * 每条文档代表论文的一个"内容块"（chunk）的向量化结果，包含：
 * </p>
 * <ul>
 *   <li>向量本身及模型版本信息</li>
 *   <li>内容块的文本、类型、位置信息（页码、bbox 坐标）</li>
 *   <li>所属论文的元数据（标题、作者、年份等）——用于搜索结果展示和过滤</li>
 *   <li>来源追溯信息（MinIO 文件路径、内容哈希）——用于调试和审计</li>
 * </ul>
 * <p>
 * </p>
 */
@Data
public class PaperVectorDocument {

    /** 文档唯一 ID（UUID），ES 文档的 _id 字段 */
    private String id;

    /** 论文 MD5，用于按论文聚合/删除，也是 ES 的 term 查询字段 */
    private String paperMd5;

    /** 链路追踪 ID，便于关联上传请求 */
    private String traceId;

    /** 内容块在论文中的序号（从 0 开始） */
    private Integer chunkIndex;

    /** 内容块类型：title / paragraph / figure / table / equation */
    private String chunkType;

    /** 上下文增强文本（包含论文元数据 + 章节路径 + 原始文本），用于向量化和全文检索 */
    private String textContent;

    /** 原始文本（不含上下文的纯文本），用于展示和精确匹配 */
    private String rawText;

    /** 该内容块的向量表示（float 数组） */
    private float[] vector;

    /** 向量化使用的 Embedding 模型版本号 */
    private String modelVersion;

    // ==================== 论文元数据（用于搜索结果展示和过滤） ====================

    /** 论文标题 */
    private String title;

    /** 作者列表 */
    private List<String> authors = new ArrayList<>();

    /** 作者文本（逗号分隔，用于全文检索） */
    private String authorText;

    /** 关键词列表 */
    private List<String> keywords = new ArrayList<>();

    /** 论文语言代码 */
    private String language;

    /** 发表年份 */
    private Integer year;

    /** 发表期刊/会议名称 */
    private String venue;

    /** DOI */
    private String doi;

    /** 原始 PDF 文件名 */
    private String fileName;

    // ==================== 内容块位置信息 ====================

    /** 内容块所属的最新一级章节标题（如 "Abstract" / "Methods"） */
    private String sectionTitle;

    /** 内容块所在的完整章节路径（如 "Abstract / Introduction / Background"） */
    private String sectionPath;

    /** 内容块起始页码（从 1 开始） */
    private Integer pageStart;

    /** 内容块结束页码 */
    private Integer pageEnd;

    /** 内容块在页面中的边界框坐标 [x0, y0, x1, y1]，用于 PDF 定位 */
    private List<Integer> bbox = new ArrayList<>();

    // ==================== 来源追溯 ====================

    /** MinerU 解析产物在 MinIO 中的对象键，用于追溯原始数据源 */
    private String sourceObjectKey;

    /** 内容块内容的 SHA-256 哈希，用于去重和变更检测 */
    private String contentHash;

    /** 文档创建时间（ISO-8601 字符串，如 "2026-05-17T18:52:54+08:00"） */
    private String createdAt;
}
