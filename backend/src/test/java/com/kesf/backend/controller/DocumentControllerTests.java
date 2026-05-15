package com.kesf.backend.controller;

import com.kesf.backend.dto.ApiResponseDTO;
import com.kesf.backend.dto.PageResultDTO;
import com.kesf.backend.dto.PaperSummaryDTO;
import com.kesf.backend.dto.UploadDocumentDTO;
import com.kesf.backend.dto.UploadProgressDTO;
import com.kesf.backend.service.DocumentService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DocumentControllerTests {

    @Mock
    private DocumentService documentService;

    @Test
    void uploadDocumentUsesClientTraceIdInApiResponse() {
        DocumentController controller = new DocumentController(documentService);
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "attention.pdf",
                "application/pdf",
                "%PDF-1.7\n%%EOF".getBytes(StandardCharsets.UTF_8)
        );
        UploadDocumentDTO uploadDocument = new UploadDocumentDTO();
        uploadDocument.setTraceId("upload-trace-001");
        uploadDocument.setFileName("attention.pdf");
        uploadDocument.setPaperMd5("6d48151d0c0140fbc3bf0cd4da041e49");
        uploadDocument.setFileSizeBytes(file.getSize());
        uploadDocument.setSubmissionTime(OffsetDateTime.parse("2026-05-13T12:30:45+08:00"));

        UploadProgressDTO progress = new UploadProgressDTO();
        progress.setPaperMd5(uploadDocument.getPaperMd5());
        when(documentService.uploadDocument(file, uploadDocument)).thenReturn(progress);

        ApiResponseDTO<UploadProgressDTO> response = controller.uploadDocument(file, uploadDocument);

        assertThat(response.getTraceId()).isEqualTo("upload-trace-001");
        assertThat(response.getData()).isSameAs(progress);
    }

    @Test
    void listDocumentsDelegatesToService() {
        DocumentController controller = new DocumentController(documentService);
        PageResultDTO<PaperSummaryDTO> page = new PageResultDTO<>(List.of(), 1, 20, 0L, false);
        when(documentService.listDocuments("attention", 2017, "NeurIPS", 1, 20)).thenReturn(page);

        ApiResponseDTO<PageResultDTO<PaperSummaryDTO>> response = controller.listDocuments(
                "attention",
                2017,
                "NeurIPS",
                1,
                20
        );

        assertThat(response.getData()).isSameAs(page);
    }
}
