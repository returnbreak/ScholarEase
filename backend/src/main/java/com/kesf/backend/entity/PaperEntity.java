package com.kesf.backend.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("papers")
public class PaperEntity {

    @TableId(value = "id", type = IdType.AUTO)
    private Long paperId;

    @TableField("paper_md5")
    private String paperMd5;

    @TableField("file_name")
    private String fileName;

    @TableField("file_size_bytes")
    private Long fileSizeBytes;

    private String title;

    @TableField("authors_json")
    private String authorsJson;

    @TableField("keywords_json")
    private String keywordsJson;

    private String language;

    private Integer year;

    private String venue;

    private String doi;

    @TableField("submission_time")
    private LocalDateTime uploadTime;

    @TableField(exist = false)
    private String fileType = "PDF";

    @TableField(exist = false)
    private String storageLocation;
}
