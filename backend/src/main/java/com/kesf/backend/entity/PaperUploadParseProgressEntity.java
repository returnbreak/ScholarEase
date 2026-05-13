package com.kesf.backend.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("paper_upload_parse_progress")
public class PaperUploadParseProgressEntity {

    @TableId(value = "trace_id", type = IdType.INPUT)
    private String traceId;

    @TableField("paper_md5")
    private String paperMd5;

    @TableField("filename")
    private String filename;

    @TableField("submission_time")
    private LocalDateTime submissionTime;

    @TableField("parse_status")
    private Integer parseStatus;
}
