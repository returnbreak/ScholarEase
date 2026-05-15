package com.kesf.backend.service;

public interface ZoteroImportService {

    ZoteroImportResult importParsedPaper(byte[] pdfBytes, String fileName, String traceId);

    record ZoteroImportResult(
            String sessionId,
            boolean canRecognize
    ) {
    }
}
