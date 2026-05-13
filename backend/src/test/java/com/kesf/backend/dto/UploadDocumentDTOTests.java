package com.kesf.backend.dto;

import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class UploadDocumentDTOTests {

    @Test
    void storesClientTraceIdAsFirstUploadField() throws Exception {
        UploadDocumentDTO dto = new UploadDocumentDTO();
        String traceId = "upload-20260513-001";

        dto.setTraceId(traceId);

        assertThat(dto.getTraceId()).isEqualTo(traceId);
        assertThat(UploadDocumentDTO.class.getDeclaredFields()[0].getName()).isEqualTo("traceId");
    }

    @Test
    void storesClientSubmissionTime() {
        OffsetDateTime submissionTime = OffsetDateTime.parse("2026-05-13T11:45:00.123+08:00");
        UploadDocumentDTO dto = new UploadDocumentDTO();

        dto.setSubmissionTime(submissionTime);

        assertThat(dto.getSubmissionTime()).isEqualTo(submissionTime);
    }
}
