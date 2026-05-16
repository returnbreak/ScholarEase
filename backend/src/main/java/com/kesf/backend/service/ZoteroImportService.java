package com.kesf.backend.service;

import java.util.List;

public interface ZoteroImportService {

    ZoteroImportResult importParsedPaper(byte[] pdfBytes, String fileName, String traceId);

    record ZoteroImportResult(
            String sessionId,
            boolean canRecognize,
            ZoteroPaperMetadata metadata
    ) {
    }

    record ZoteroPaperMetadata(
            String title,
            List<String> authors,
            List<String> keywords,
            String language,
            Integer year,
            String venue,
            String doi
    ) {
    }
}
