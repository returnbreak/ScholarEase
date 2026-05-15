package com.kesf.backend.service;

import com.kesf.backend.dto.UploadDocumentDTO;
import com.kesf.backend.entity.PaperUploadParseProgressEntity;
import com.kesf.backend.mapper.PaperUploadParseProgressMapper;
import com.kesf.backend.service.impl.PaperUploadParseProgressServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class PaperUploadParseProgressServiceTests {

    @Mock
    private PaperUploadParseProgressMapper progressMapper;

    @InjectMocks
    private PaperUploadParseProgressServiceImpl progressService;

    @Test
    void recordsUploadProgressFromClientTraceIdAndFileFields() {
        UploadDocumentDTO dto = new UploadDocumentDTO();
        dto.setTraceId("trace-001");
        dto.setPaperMd5("1f3870be274f6c49b3e31a0c6728957f");
        dto.setFileName("paper.pdf");
        dto.setSubmissionTime(OffsetDateTime.parse("2026-05-13T12:30:45+08:00"));

        progressService.recordUploadProgress(dto);

        ArgumentCaptor<PaperUploadParseProgressEntity> captor =
                ArgumentCaptor.forClass(PaperUploadParseProgressEntity.class);
        verify(progressMapper).insert(captor.capture());

        PaperUploadParseProgressEntity entity = captor.getValue();
        assertThat(entity.getTraceId()).isEqualTo("trace-001");
        assertThat(entity.getPaperMd5()).isEqualTo("1f3870be274f6c49b3e31a0c6728957f");
        assertThat(entity.getFilename()).isEqualTo("paper.pdf");
        assertThat(entity.getSubmissionTime()).isEqualTo(LocalDateTime.parse("2026-05-13T12:30:45"));
        assertThat(entity.getParseStatus()).isEqualTo(1);
    }

    @Test
    void keepsSubmissionTimeNullWhenClientDoesNotProvideIt() {
        UploadDocumentDTO dto = new UploadDocumentDTO();
        dto.setTraceId("trace-002");
        dto.setPaperMd5("1f3870be274f6c49b3e31a0c6728957f");
        dto.setFileName("paper.pdf");

        progressService.recordUploadProgress(dto);

        ArgumentCaptor<PaperUploadParseProgressEntity> captor =
                ArgumentCaptor.forClass(PaperUploadParseProgressEntity.class);
        verify(progressMapper).insert(captor.capture());

        assertThat(captor.getValue().getSubmissionTime()).isNull();
        assertThat(captor.getValue().getParseStatus()).isEqualTo(1);
    }

    @Test
    void storesUtcSubmissionTimeAsBeijingLocalTime() {
        UploadDocumentDTO dto = new UploadDocumentDTO();
        dto.setTraceId("trace-utc");
        dto.setPaperMd5("1f3870be274f6c49b3e31a0c6728957f");
        dto.setFileName("paper.pdf");
        dto.setSubmissionTime(OffsetDateTime.parse("2026-05-15T08:23:17Z"));

        progressService.recordUploadProgress(dto);

        ArgumentCaptor<PaperUploadParseProgressEntity> captor =
                ArgumentCaptor.forClass(PaperUploadParseProgressEntity.class);
        verify(progressMapper).insert(captor.capture());

        assertThat(captor.getValue().getSubmissionTime())
                .isEqualTo(LocalDateTime.parse("2026-05-15T16:23:17"));
    }

    @Test
    void updatesParseStatusByTraceId() {
        progressService.updateParseStatus("trace-001", 2);

        ArgumentCaptor<PaperUploadParseProgressEntity> captor =
                ArgumentCaptor.forClass(PaperUploadParseProgressEntity.class);
        verify(progressMapper).update(captor.capture(), any());

        PaperUploadParseProgressEntity entity = captor.getValue();
        assertThat(entity.getParseStatus()).isEqualTo(2);
    }
}
