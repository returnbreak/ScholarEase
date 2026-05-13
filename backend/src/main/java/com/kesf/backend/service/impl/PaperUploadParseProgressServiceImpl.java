package com.kesf.backend.service.impl;

import com.kesf.backend.dto.UploadDocumentDTO;
import com.kesf.backend.entity.PaperUploadParseProgressEntity;
import com.kesf.backend.mapper.PaperUploadParseProgressMapper;
import com.kesf.backend.service.PaperUploadParseProgressService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class PaperUploadParseProgressServiceImpl implements PaperUploadParseProgressService {

    private static final int PARSE_STATUS_PARSING = 1;

    private final PaperUploadParseProgressMapper progressMapper;

    @Override
    public void recordUploadProgress(UploadDocumentDTO uploadDocument) {
        PaperUploadParseProgressEntity progress = new PaperUploadParseProgressEntity();
        progress.setTraceId(uploadDocument.getTraceId());
        progress.setPaperMd5(uploadDocument.getPaperMd5());
        progress.setFilename(uploadDocument.getFileName());
        if (uploadDocument.getSubmissionTime() != null) {
            progress.setSubmissionTime(uploadDocument.getSubmissionTime().toLocalDateTime());
        }
        progress.setParseStatus(PARSE_STATUS_PARSING);

        progressMapper.insert(progress);
    }
}
