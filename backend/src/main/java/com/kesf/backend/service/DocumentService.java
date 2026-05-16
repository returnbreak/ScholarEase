package com.kesf.backend.service;

import com.kesf.backend.dto.PageResultDTO;
import com.kesf.backend.dto.PaperDetailDTO;
import com.kesf.backend.dto.PaperSummaryDTO;
import com.kesf.backend.dto.UploadDocumentDTO;
import com.kesf.backend.dto.UploadProgressDTO;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

public interface DocumentService {

    UploadProgressDTO uploadDocument(MultipartFile file, UploadDocumentDTO uploadDocument);

    PageResultDTO<PaperSummaryDTO> listDocuments(String keyword, Integer year, String venue, Integer page, Integer pageSize);

    PaperDetailDTO getDocument(Long paperId);

    Map<String, Object> deleteDocument(Long paperId);
}
