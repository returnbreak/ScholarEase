package com.kesf.backend.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kesf.backend.config.MinerUProperties;
import com.kesf.backend.dto.UploadDocumentDTO;
import com.kesf.backend.dto.UploadProgressDTO;
import com.kesf.backend.entity.PaperEntity;
import com.kesf.backend.exception.BusinessException;
import com.kesf.backend.exception.ErrorCode;
import com.kesf.backend.mapper.PaperMapper;
import com.kesf.backend.service.impl.DocumentServiceImpl;
import com.kesf.backend.utils.MinerUClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;

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

    @Mock
    private MinerUClient minerUClient;

    private DocumentServiceImpl documentService;

    @BeforeEach
    void setUp() {
        MinerUProperties minerUProperties = new MinerUProperties();
        minerUProperties.getPolling().setMaxAttempts(1);
        documentService = new DocumentServiceImpl(paperMapper, progressService, minerUClient, minerUProperties);
    }

    @Test
    void uploadDocumentVerifiesMetadataRecordsProgressAndRunsMinerUWhenMd5IsNew() {
        UploadDocumentDTO dto = uploadDto(PDF_MD5, (long) PDF_BYTES.length);
        MockMultipartFile file = pdfFile();
        when(paperMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        when(minerUClient.requestSignedUploadUrl("attention.pdf", "trace-001"))
                .thenReturn(new MinerUClient.SignedUpload("batch-001", "https://signed.example/upload"));
        when(minerUClient.getBatchResult("batch-001", "trace-001"))
                .thenReturn(new MinerUClient.BatchFileResult("done", "", "https://mineru.example/full.zip"));

        UploadProgressDTO result = documentService.uploadDocument(file, dto);

        verify(progressService).recordUploadProgress(dto);
        verify(minerUClient).uploadToSignedUrl("https://signed.example/upload", PDF_BYTES);
        verify(progressService).updateParseStatus("trace-001", 2);
        verify(paperMapper, never()).insert(any(PaperEntity.class));

        assertThat(result.getTraceId()).isEqualTo("trace-001");
        assertThat(result.getPaperMd5()).isEqualTo(PDF_MD5);
        assertThat(result.getFileName()).isEqualTo("attention.pdf");
        assertThat(result.getFileSizeBytes()).isEqualTo((long) PDF_BYTES.length);
        assertThat(result.getSubmissionTime()).isEqualTo(OffsetDateTime.parse("2026-05-13T12:30:45+08:00"));
        assertThat(result.getParseStatus()).isEqualTo("PARSED");
        assertThat(result.getFullZipUrl()).isEqualTo("https://mineru.example/full.zip");
    }

    @Test
    void uploadDocumentMarksProgressFailedWhenMinerUParseFails() {
        UploadDocumentDTO dto = uploadDto(PDF_MD5, (long) PDF_BYTES.length);
        MockMultipartFile file = pdfFile();
        when(paperMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        when(minerUClient.requestSignedUploadUrl("attention.pdf", "trace-001"))
                .thenReturn(new MinerUClient.SignedUpload("batch-001", "https://signed.example/upload"));
        when(minerUClient.getBatchResult("batch-001", "trace-001"))
                .thenReturn(new MinerUClient.BatchFileResult("done", "", ""));

        assertThatThrownBy(() -> documentService.uploadDocument(file, dto))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.MINERU_PARSE_FAILED);

        verify(progressService).recordUploadProgress(dto);
        verify(progressService).updateParseStatus("trace-001", 3);
        verify(paperMapper, never()).insert(any(PaperEntity.class));
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

    @Test
    void listDocumentsReturnsPagedPaperSummaries() {
        PaperEntity paper = new PaperEntity();
        paper.setPaperId(10001L);
        paper.setPaperMd5(PDF_MD5);
        paper.setFileName("attention.pdf");
        paper.setFileSizeBytes((long) PDF_BYTES.length);
        paper.setTitle("Attention Is All You Need");
        paper.setAuthorsJson("[\"Ashish Vaswani\",\"Noam Shazeer\"]");
        paper.setUploadTime(LocalDateTime.of(2026, 5, 13, 12, 30, 45));
        paper.setYear(2017);
        paper.setVenue("NeurIPS");

        Page<PaperEntity> page = new Page<>(1, 20, 1);
        page.setRecords(List.of(paper));
        when(paperMapper.selectPage(any(Page.class), any(Wrapper.class))).thenReturn(page);

        var result = documentService.listDocuments("attention", 2017, "NeurIPS", 1, 20);

        assertThat(result.getTotal()).isEqualTo(1);
        assertThat(result.getHasNext()).isFalse();
        assertThat(result.getItems()).hasSize(1);
        assertThat(result.getItems().get(0).getTitle()).isEqualTo("Attention Is All You Need");
        assertThat(result.getItems().get(0).getAuthors()).containsExactly("Ashish Vaswani", "Noam Shazeer");
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
