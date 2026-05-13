package com.kesf.backend.dto;

import lombok.Data;

import java.time.OffsetDateTime;

@Data
public class UploadProgressDTO {

    private String traceId;

    private String paperMd5;

    private String fileName;

    private Long fileSizeBytes;

    private OffsetDateTime submissionTime;

    private String parseStatus;
}
