package com.kesf.backend.service;

import com.kesf.backend.dto.UploadDocumentDTO;

public interface PaperUploadParseProgressService {

    void recordUploadProgress(UploadDocumentDTO uploadDocument);
}
