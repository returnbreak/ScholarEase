package com.kesf.backend.dto;

import lombok.Data;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

@Data
public class PaperSummaryDTO {

    /**
     * papers.id，文献主表自增主键。
     */
    private Long paperId;

    /**
     * papers.paper_md5，文件内容 MD5。
     *
     * 这是当前上传阶段的核心查重依据，同一份 PDF 再次上传时会命中该字段。
     */
    private String paperMd5;

    /**
     * papers.file_name，用户上传时的原始文件名。
     */
    private String fileName;

    /**
     * papers.file_size_bytes，后端复核后的文件字节数。
     */
    private Long fileSizeBytes;

    /**
     * 当前 MVP 只支持 PDF，因此返回给前端时固定为 PDF。
     */
    private String fileType = "PDF";

    /**
     * papers.title。
     *
     * 上传阶段还未经过 MinerU 解析，默认用“文件名去掉扩展名”作为临时标题；
     * 后续解析成功后再用论文真实标题覆盖。
     */
    private String title;

    /**
     * authors_json 反序列化后的作者列表。
     *
     * 上传初始阶段为空数组，避免前端处理 null。
     */
    private List<String> authors = new ArrayList<>();

    /**
     * 前端列表展示用的作者文本。
     *
     * 由 authors 用逗号拼接生成，例如 "Alice, Bob"。
     */
    private String authorText = "";

    /**
     * 文献对象存储位置。
     *
     * 当前 papers 表还没有单独保存 storage_location，
     * 因此后端按 paperId 生成一个稳定的展示路径。
     */
    private String storageLocation;

    /**
     * papers.submission_time 转换后的接口时间。
     *
     * 数据库使用 LocalDateTime/DATETIME，接口层使用带时区的 OffsetDateTime，
     * 便于前端直接格式化展示。
     */
    private OffsetDateTime uploadTime;

    /**
     * papers.year，发表年份。
     *
     * 上传阶段通常为空，后续由解析或元数据补全流程更新。
     */
    private Integer year;

    /**
     * papers.venue，期刊、会议或出版源。
     */
    private String venue;

    /**
     * papers.doi，论文 DOI。
     */
    private String doi;

    /**
     * keywords_json 反序列化后的关键词列表。
     */
    private List<String> keywords = new ArrayList<>();

}
