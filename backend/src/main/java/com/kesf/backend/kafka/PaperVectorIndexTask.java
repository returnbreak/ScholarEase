package com.kesf.backend.kafka;

import lombok.Data;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 论文向量索引任务——Kafka 消息体。
 * <p>
 * 封装了论文上传后需要执行的向量化任务所需的所有信息，包括：
 * </p>
 * <ul>
 *   <li>论文元数据（标题、作者、关键词、年份、期刊/会议、DOI 等）</li>
 *   <li>MinIO 中的解析产物路径（MinerU 输出的 content_list_v2.json、full.md）</li>
 *   <li>任务追踪信息（taskId、traceId）</li>
 *   <li>向量化模型版本号</li>
 * </ul>
 * <p>
 * 该对象在生产者端被序列化为 JSON 发送到 Kafka Topic，消费者端反序列化后执行实际的向量化与 Elasticsearch 写入。
 * </p>
 */
@Data // Lombok：自动生成 getter/setter/toString/equals/hashCode
public class PaperVectorIndexTask {

    /** 任务唯一标识，格式为 {@code traceId:paperMd5}，用于关联上传请求与向量化任务 */
    private String taskId;

    /** 分布式链路追踪 ID，由前端/网关生成，贯穿整个上传→解析→向量化流程 */
    private String traceId;

    /** 论文 PDF 文件的 MD5 哈希值，用作 Kafka 消息的 key 以确保同一论文的消息有序 */
    private String paperMd5;

    /** 原始 PDF 文件名 */
    private String fileName;

    /** PDF 文件大小（字节） */
    private Long fileSizeBytes;

    /** 论文提交时间（带时区信息） */
    private OffsetDateTime submissionTime;

    /** MinIO 存储桶名称，存储论文解析产物的桶 */
    private String minioBucket;

    /** MinerU 解析产物的 content_list_v2.json 在 MinIO 中的对象键 */
    private String contentListObjectKey;

    /** MinerU 解析产物的完整 Markdown 文件在 MinIO 中的对象键 */
    private String fullMarkdownObjectKey;

    // ==================== 论文元数据 ====================

    /** 论文标题（优先使用 Zotero 识别的标题，回退到文件名推断） */
    private String title;

    /** 作者列表 */
    private List<String> authors = new ArrayList<>();

    /** 关键词列表 */
    private List<String> keywords = new ArrayList<>();

    /** 论文语言代码（如 en、zh），默认 "en" */
    private String language;

    /** 发表年份 */
    private Integer year;

    /** 发表期刊/会议名称 */
    private String venue;

    /** 数字对象唯一标识符（DOI） */
    private String doi;

    /** 向量化使用的 Embedding 模型版本，如 "BAAI/bge-m3" */
    private String modelVersion;
}
