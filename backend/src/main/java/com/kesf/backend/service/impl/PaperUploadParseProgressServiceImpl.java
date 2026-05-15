package com.kesf.backend.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.kesf.backend.dto.UploadDocumentDTO;
import com.kesf.backend.entity.PaperUploadParseProgressEntity;
import com.kesf.backend.mapper.PaperUploadParseProgressMapper;
import com.kesf.backend.service.PaperUploadParseProgressService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.ZoneOffset;

@Service
@RequiredArgsConstructor
public class PaperUploadParseProgressServiceImpl implements PaperUploadParseProgressService {

    private static final int PARSE_STATUS_PARSING = 1;
    private static final ZoneOffset BEIJING_OFFSET = ZoneOffset.ofHours(8);

    private final PaperUploadParseProgressMapper progressMapper;

    @Override
    public void recordUploadProgress(UploadDocumentDTO uploadDocument) {
        PaperUploadParseProgressEntity progress = new PaperUploadParseProgressEntity();
        progress.setTraceId(uploadDocument.getTraceId());
        progress.setPaperMd5(uploadDocument.getPaperMd5());
        progress.setFilename(uploadDocument.getFileName());
        if (uploadDocument.getSubmissionTime() != null) {
            progress.setSubmissionTime(uploadDocument.getSubmissionTime()
                    .withOffsetSameInstant(BEIJING_OFFSET)
                    .toLocalDateTime());
        }
        progress.setParseStatus(PARSE_STATUS_PARSING);

        progressMapper.insert(progress);
    }

    @Override
    public void updateParseStatus(String traceId, int parseStatus) {
        PaperUploadParseProgressEntity progress = new PaperUploadParseProgressEntity();
        progress.setParseStatus(parseStatus);

        progressMapper.update(progress, new LambdaUpdateWrapper<PaperUploadParseProgressEntity>()
                .eq(PaperUploadParseProgressEntity::getTraceId, traceId));
    }
}
