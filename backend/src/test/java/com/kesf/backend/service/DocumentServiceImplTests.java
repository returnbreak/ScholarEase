package com.kesf.backend.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.kesf.backend.dto.UploadDocumentDTO;
import com.kesf.backend.dto.UploadProgressDTO;
import com.kesf.backend.entity.PaperEntity;
import com.kesf.backend.exception.BusinessException;
import com.kesf.backend.exception.ErrorCode;
import com.kesf.backend.mapper.PaperMapper;
import com.kesf.backend.service.impl.DocumentServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DocumentServiceImplTests {

    private static final byte[] PDF_BYTES = "%PDF-1.7\nScholarEase\n%%EOF".getBytes(StandardCharsets.UTF_8);
    private static final String PDF_MD5 = "8bbd2462a67b57f1b9f21b95c69043bc";

    @Mock
    private PaperMapper paperMapper;

    @Mock
    private PaperUploadParseProgressService progressService;

    @InjectMocks
    private DocumentServiceImpl documentService;

    @Test
    void uploadDocumentVerifiesMetadataAndRecordsParsingProgressWhenMd5IsNew() {
        UploadDocumentDTO dto = uploadDto(PDF_MD5, (long) PDF_BYTES.length);
        MockMultipartFile file = pdfFile();
        when(paperMapper.selectOne(any(Wrapper.class))).thenReturn(null);

        UploadProgressDTO result = documentService.uploadDocument(file, dto);

        verify(progressService).recordUploadProgress(dto);
        verify(paperMapper, never()).insert(any(PaperEntity.class));

        assertThat(result.getTraceId()).isEqualTo("trace-001");
        assertThat(result.getPaperMd5()).isEqualTo(PDF_MD5);
        assertThat(result.getFileName()).isEqualTo("attention.pdf");
        assertThat(result.getFileSizeBytes()).isEqualTo((long) PDF_BYTES.length);
        assertThat(result.getSubmissionTime()).isEqualTo(OffsetDateTime.parse("2026-05-13T12:30:45+08:00"));
        assertThat(result.getParseStatus()).isEqualTo("PARSING");
    }

    @Test
    void uploadDocumentRejectsWhenClientMd5OrSizeDoesNotMatchActualFile() {
        UploadDocumentDTO dto = uploadDto("1f3870be274f6c49b3e31a0c6728957f", 999L);

        assertThatThrownBy(() -> documentService.uploadDocument(pdfFile(), dto))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.FILE_METADATA_MISMATCH);

        verify(paperMapper, never()).insert(any(PaperEntity.class));
        verify(progressService, never()).recordUploadProgress(any());
    }

    @Test
    void uploadDocumentRejectsDuplicatePaperMd5WithoutInsertingNewPaper() {
        UploadDocumentDTO dto = uploadDto(PDF_MD5, (long) PDF_BYTES.length);
        PaperEntity existingPaper = new PaperEntity();
        existingPaper.setPaperId(10001L);
        existingPaper.setPaperMd5(PDF_MD5);
        existingPaper.setFileName("attention.pdf");
        existingPaper.setFileSizeBytes((long) PDF_BYTES.length);
        existingPaper.setTitle("Attention Is All You Need");
        when(paperMapper.selectOne(any(Wrapper.class))).thenReturn(existingPaper);

        assertThatThrownBy(() -> documentService.uploadDocument(pdfFile(), dto))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.DUPLICATE_PAPER);

        verify(progressService, never()).recordUploadProgress(any());
        verify(paperMapper, never()).insert(any(PaperEntity.class));
    }

    private static MockMultipartFile pdfFile() {
        return new MockMultipartFile("file", "attention.pdf", "application/pdf", PDF_BYTES);
    }

    private static UploadDocumentDTO uploadDto(String paperMd5, Long fileSizeBytes) {
        UploadDocumentDTO dto = new UploadDocumentDTO();
        dto.setTraceId("trace-001");
        dto.setFileName("attention.pdf");
        dto.setPaperMd5(paperMd5);
        dto.setFileSizeBytes(fileSizeBytes);
        dto.setSubmissionTime(OffsetDateTime.parse("2026-05-13T12:30:45+08:00"));
        return dto;
    }
}
