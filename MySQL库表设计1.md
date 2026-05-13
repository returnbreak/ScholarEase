## 论文主表

`papers` 只保存已经完成解析确认，并且已经落到 Zotero 和 MinIO 的文献数据。
上传阶段不再直接写入该表，因此这里不再保存 `parse_status`。

```sql
CREATE TABLE papers(
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT 'ID',
    paper_md5 VARCHAR(32) NOT NULL UNIQUE COMMENT '文件MD5标识',
    file_name VARCHAR(512) NOT NULL COMMENT '上传文件名称',
    file_size_bytes BIGINT UNSIGNED NOT NULL COMMENT '文件大小，单位字节',
    title VARCHAR(1024) NOT NULL COMMENT '论文标题',
    authors_json JSON NULL COMMENT '作者列表',
    keywords_json JSON NULL COMMENT '关键词',
    language VARCHAR(32) NOT NULL DEFAULT 'en' COMMENT '论文语言',
    year INT UNSIGNED NULL COMMENT '发表年份',
    venue VARCHAR(512) NULL COMMENT '期刊/会议/出版源',
    doi VARCHAR(255) NULL COMMENT 'DOI',
    submission_time DATETIME(3) NULL COMMENT '提交时间'
)ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='论文主表';
```

## 文献上传解析进度表

`paper_upload_parse_progress` 保存上传请求和解析状态。
上传成功后先写入该表，并将 `parse_status` 初始化为 `1`。
解析完成、Zotero 写入和 MinIO 落盘都确认成功后，再创建 `papers` 主表记录。

```sql
CREATE TABLE paper_upload_parse_progress(
    trace_id VARCHAR(64) NOT NULL PRIMARY KEY COMMENT '前端上传请求追踪ID',
    paper_md5 VARCHAR(32) NOT NULL COMMENT '文件MD5标识',
    filename VARCHAR(512) NOT NULL COMMENT '上传文件名称',
    submission_time DATETIME NULL COMMENT '提交时间',
    parse_status INT NOT NULL DEFAULT 1 COMMENT '解析状态：0待解析，1解析中，2解析完成，3解析失败',
    KEY idx_upload_parse_progress_paper_md5 (paper_md5),
    KEY idx_upload_parse_progress_submission_time (submission_time),
    KEY idx_upload_parse_progress_parse_status (parse_status)
)ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='文献上传解析进度表';
```
