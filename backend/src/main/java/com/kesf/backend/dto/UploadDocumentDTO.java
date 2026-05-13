package com.kesf.backend.dto;

import lombok.Data;

import java.time.OffsetDateTime;

@Data
public class UploadDocumentDTO {

    private String traceId;

    private String fileName;

    private String paperMd5;

    private Long fileSizeBytes;

    private OffsetDateTime submissionTime;
}
