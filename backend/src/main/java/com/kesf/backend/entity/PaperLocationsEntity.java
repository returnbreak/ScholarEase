package com.kesf.backend.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("paper_locations")
public class PaperLocationsEntity {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("paper_id")
    private Long paperId;

    @TableField("paper_md5")
    private String paperMd5;

    @TableField("file_name")
    private String fileName;

    @TableField("submission_time")
    private LocalDateTime submissionTime;

    @TableField("minio_bucket")
    private String minioBucket;

    @TableField("minio_original_key")
    private String minioOriginalKey;

    @TableField("minio_parsed_prefix")
    private String minioParsedPrefix;

    @TableField("zotero_item_key")
    private String zoteroItemKey;

    @TableField("zotero_collection_name")
    private String zoteroCollectionName;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("updated_at")
    private LocalDateTime updatedAt;
}
