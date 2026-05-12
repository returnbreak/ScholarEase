## 论文主表

```sql
CREATE TABLE papers(
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT 'ID',
    paper_md5 VARCHAR(32) NOT NULL UNIQUE COMMENT '文件标识',
	title VARCHAR(1024) NOT NULL COMMENT '论文标题',
    authors_json JSON NULL COMMENT '作者列表',
    keywords_json JSON NULL COMMENT '关键词',
    language VARCHAR(32) NOT NULL DEFAULT 'en' COMMENT '论文语言',
    year INT UNSIGNED NULL COMMENT '发表年份',
    venue VARCHAR(512) NULL COMMENT '期刊/会议/出版源',
    doi VARCHAR(255) NULL COMMENT 'DOI',
    submission_time DATETIME(3) NULL COMMENT '提交时间',
    parse_status INT NOT NULL DEFAULT 0 COMMENT '解析状态'
)ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='论文主表';
```

