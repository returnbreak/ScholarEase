package com.kesf.backend.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kesf.backend.config.MinerUProperties;
import com.kesf.backend.dto.DuplicatePaperDataDTO;
import com.kesf.backend.dto.PageResultDTO;
import com.kesf.backend.dto.PaperSummaryDTO;
import com.kesf.backend.dto.UploadDocumentDTO;
import com.kesf.backend.dto.UploadProgressDTO;
import com.kesf.backend.entity.PaperEntity;
import com.kesf.backend.exception.BusinessException;
import com.kesf.backend.exception.ErrorCode;
import com.kesf.backend.mapper.PaperMapper;
import com.kesf.backend.service.DocumentService;
import com.kesf.backend.service.PaperUploadParseProgressService;
import com.kesf.backend.utils.Md5Utils;
import com.kesf.backend.utils.MinerUClient;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class DocumentServiceImpl implements DocumentService {

    private static final long MAX_FILE_SIZE_BYTES = 200L * 1024L * 1024L;
    private static final int PARSE_STATUS_PARSED_VALUE = 2;
    private static final int PARSE_STATUS_FAILED_VALUE = 3;
    private static final String PARSE_STATUS_PARSED = "PARSED";
    private static final String MINERU_STATE_RUNNING = "running";
    private static final String MINERU_STATE_DONE = "done";
    private static final String MINERU_STATE_FAILED = "failed";

    private final PaperMapper paperMapper;
    private final PaperUploadParseProgressService progressService;
    private final MinerUClient minerUClient;
    private final MinerUProperties minerUProperties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public UploadProgressDTO uploadDocument(MultipartFile file, UploadDocumentDTO uploadDocument) {
        validatePdfFile(file, uploadDocument);

        String actualPaperMd5 = calculateMd5(file);
        long actualFileSizeBytes = file.getSize();
        validateClientMetadata(uploadDocument, actualPaperMd5, actualFileSizeBytes);

        PaperEntity duplicatePaper = paperMapper.selectOne(new LambdaQueryWrapper<PaperEntity>()
                .eq(PaperEntity::getPaperMd5, actualPaperMd5)
                .last("LIMIT 1"));
        if (duplicatePaper != null) {
            throw new BusinessException(
                    ErrorCode.DUPLICATE_PAPER,
                    ErrorCode.DUPLICATE_PAPER.getMessage(),
                    new DuplicatePaperDataDTO("PDF_MD5_MATCHED", toSummary(duplicatePaper))
            );
        }

        progressService.recordUploadProgress(uploadDocument);
        MinerUParseResult parseResult = parseWithMinerU(file, uploadDocument);

        return toUploadProgress(uploadDocument, actualPaperMd5, actualFileSizeBytes, parseResult);
    }

    @Override
    public PageResultDTO<PaperSummaryDTO> listDocuments(
            String keyword,
            Integer year,
            String venue,
            Integer page,
            Integer pageSize
    ) {
        int safePage = page == null || page < 1 ? 1 : page;
        int safePageSize = pageSize == null || pageSize < 1 ? 20 : Math.min(pageSize, 100);

        LambdaQueryWrapper<PaperEntity> queryWrapper = new LambdaQueryWrapper<>();
        String trimmedKeyword = trimToNull(keyword);
        if (trimmedKeyword != null) {
            queryWrapper.and(wrapper -> wrapper
                    .like(PaperEntity::getTitle, trimmedKeyword)
                    .or()
                    .like(PaperEntity::getFileName, trimmedKeyword)
                    .or()
                    .like(PaperEntity::getAuthorsJson, trimmedKeyword)
                    .or()
                    .like(PaperEntity::getDoi, trimmedKeyword));
        }
        if (year != null) {
            queryWrapper.eq(PaperEntity::getYear, year);
        }
        String trimmedVenue = trimToNull(venue);
        if (trimmedVenue != null) {
            queryWrapper.like(PaperEntity::getVenue, trimmedVenue);
        }
        queryWrapper.orderByDesc(PaperEntity::getUploadTime).orderByDesc(PaperEntity::getPaperId);

        Page<PaperEntity> resultPage = paperMapper.selectPage(new Page<>(safePage, safePageSize), queryWrapper);
        List<PaperSummaryDTO> items = resultPage.getRecords().stream()
                .map(this::toSummary)
                .toList();

        return new PageResultDTO<>(
                items,
                safePage,
                safePageSize,
                resultPage.getTotal(),
                resultPage.getCurrent() < resultPage.getPages()
        );
    }

    private MinerUParseResult parseWithMinerU(MultipartFile file, UploadDocumentDTO uploadDocument) {
        try {
            MinerUParseResult parseResult = parseByMinerU(
                    file,
                    uploadDocument.getTraceId(),
                    safeFileName(uploadDocument.getFileName())
            );
            progressService.updateParseStatus(uploadDocument.getTraceId(), PARSE_STATUS_PARSED_VALUE);
            return parseResult;
        } catch (BusinessException exception) {
            progressService.updateParseStatus(uploadDocument.getTraceId(), PARSE_STATUS_FAILED_VALUE);
            throw exception;
        } catch (RuntimeException exception) {
            progressService.updateParseStatus(uploadDocument.getTraceId(), PARSE_STATUS_FAILED_VALUE);
            throw new BusinessException(ErrorCode.MINERU_PARSE_FAILED, ErrorCode.MINERU_PARSE_FAILED.getMessage());
        }
    }

    private MinerUParseResult parseByMinerU(MultipartFile file, String traceId, String fileName) {
        if (!minerUProperties.isEnabled()) {
            throw minerUFailed("MinerU parsing is disabled");
        }

        byte[] fileBytes = readFileBytes(file);
        MinerUClient.SignedUpload signedUpload = minerUClient.requestSignedUploadUrl(fileName, traceId);
        minerUClient.uploadToSignedUrl(signedUpload.signedUrl(), fileBytes);

        int maxAttempts = Math.max(1, minerUProperties.getPolling().getMaxAttempts());
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            MinerUClient.BatchFileResult result = minerUClient.getBatchResult(signedUpload.batchId(), traceId);
            String state = result.state();

            if (MINERU_STATE_DONE.equalsIgnoreCase(state)) {
                if (!StringUtils.hasText(result.fullZipUrl())) {
                    throw minerUFailed("MinerU finished without full_zip_url");
                }
                return new MinerUParseResult(signedUpload.batchId(), result.fullZipUrl());
            }

            if (MINERU_STATE_FAILED.equalsIgnoreCase(state)) {
                throw minerUFailed(StringUtils.hasText(result.errorMessage())
                        ? result.errorMessage()
                        : "MinerU batch parse failed");
            }

            if (attempt < maxAttempts) {
                sleepBeforeNextMinerUPoll(state);
            }
        }

        throw minerUFailed("MinerU batch parse timed out");
    }

    private byte[] readFileBytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException exception) {
            throw new BusinessException(ErrorCode.UPLOAD_FAILED, "Failed to read uploaded file");
        }
    }

    private void sleepBeforeNextMinerUPoll(String state) {
        if (!MINERU_STATE_RUNNING.equalsIgnoreCase(state)) {
            return;
        }
        try {
            Thread.sleep(minerUProperties.getPolling().getInterval().toMillis());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw minerUFailed("MinerU polling interrupted");
        }
    }

    private BusinessException minerUFailed(String message) {
        return new BusinessException(ErrorCode.MINERU_PARSE_FAILED, message);
    }

    private void validatePdfFile(MultipartFile file, UploadDocumentDTO uploadDocument) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_FILE_TYPE);
        }
        if (file.getSize() > MAX_FILE_SIZE_BYTES) {
            throw new BusinessException(ErrorCode.FILE_TOO_LARGE);
        }

        String fileName = uploadDocument == null ? null : uploadDocument.getFileName();
        String contentType = file.getContentType();
        boolean pdfName = StringUtils.hasText(fileName) && fileName.toLowerCase(Locale.ROOT).endsWith(".pdf");
        boolean pdfContentType = "application/pdf".equalsIgnoreCase(contentType);
        if (!pdfName || !pdfContentType) {
            throw new BusinessException(ErrorCode.INVALID_FILE_TYPE);
        }
    }

    private String calculateMd5(MultipartFile file) {
        try {
            return Md5Utils.md5Hex(file.getInputStream()).toLowerCase(Locale.ROOT);
        } catch (IOException exception) {
            throw new BusinessException(ErrorCode.UPLOAD_FAILED, "Failed to read uploaded file");
        }
    }

    private void validateClientMetadata(
            UploadDocumentDTO uploadDocument,
            String actualPaperMd5,
            long actualFileSizeBytes
    ) {
        if (uploadDocument == null
                || !StringUtils.hasText(uploadDocument.getPaperMd5())
                || uploadDocument.getFileSizeBytes() == null
                || uploadDocument.getSubmissionTime() == null) {
            throw metadataMismatch(uploadDocument, actualPaperMd5, actualFileSizeBytes);
        }

        String expectedPaperMd5 = uploadDocument.getPaperMd5().trim().toLowerCase(Locale.ROOT);
        Long expectedFileSizeBytes = uploadDocument.getFileSizeBytes();
        if (!actualPaperMd5.equals(expectedPaperMd5) || actualFileSizeBytes != expectedFileSizeBytes) {
            throw metadataMismatch(uploadDocument, actualPaperMd5, actualFileSizeBytes);
        }
    }

    private BusinessException metadataMismatch(
            UploadDocumentDTO uploadDocument,
            String actualPaperMd5,
            long actualFileSizeBytes
    ) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("expectedPaperMd5", uploadDocument == null ? null : uploadDocument.getPaperMd5());
        data.put("actualPaperMd5", actualPaperMd5);
        data.put("expectedFileSizeBytes", uploadDocument == null ? null : uploadDocument.getFileSizeBytes());
        data.put("actualFileSizeBytes", actualFileSizeBytes);
        return new BusinessException(ErrorCode.FILE_METADATA_MISMATCH, ErrorCode.FILE_METADATA_MISMATCH.getMessage(), data);
    }

    private UploadProgressDTO toUploadProgress(
            UploadDocumentDTO uploadDocument,
            String actualPaperMd5,
            long actualFileSizeBytes,
            MinerUParseResult parseResult
    ) {
        UploadProgressDTO progress = new UploadProgressDTO();
        progress.setTraceId(uploadDocument.getTraceId());
        progress.setPaperMd5(actualPaperMd5);
        progress.setFileName(safeFileName(uploadDocument.getFileName()));
        progress.setFileSizeBytes(actualFileSizeBytes);
        progress.setSubmissionTime(uploadDocument.getSubmissionTime());
        progress.setParseStatus(PARSE_STATUS_PARSED);
        progress.setFullZipUrl(parseResult.fullZipUrl());
        return progress;
    }

    private PaperSummaryDTO toSummary(PaperEntity paper) {
        PaperSummaryDTO summary = new PaperSummaryDTO();
        summary.setPaperId(paper.getPaperId());
        summary.setPaperMd5(paper.getPaperMd5());
        summary.setFileName(paper.getFileName());
        summary.setFileSizeBytes(paper.getFileSizeBytes());
        summary.setTitle(paper.getTitle());
        List<String> authors = parseJsonArray(paper.getAuthorsJson());
        summary.setAuthors(authors);
        summary.setAuthorText(String.join(", ", authors));
        summary.setStorageLocation(storageLocation(paper));
        summary.setUploadTime(toOffsetDateTime(paper));
        summary.setYear(paper.getYear());
        summary.setVenue(paper.getVenue());
        summary.setDoi(paper.getDoi());
        return summary;
    }

    private List<String> parseJsonArray(String json) {
        if (!StringUtils.hasText(json)) {
            return new ArrayList<>();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {
            });
        } catch (IOException exception) {
            return new ArrayList<>();
        }
    }

    private OffsetDateTime toOffsetDateTime(PaperEntity paper) {
        if (paper.getUploadTime() == null) {
            return null;
        }
        return paper.getUploadTime().atOffset(ZoneOffset.ofHours(8));
    }

    private String storageLocation(PaperEntity paper) {
        if (paper.getPaperId() == null) {
            return "";
        }
        return "papers/" + paper.getPaperId() + "/original/";
    }

    private String safeFileName(String fileName) {
        String safeName = StringUtils.getFilename(fileName);
        return StringUtils.hasText(safeName) ? safeName : fileName;
    }

    private String trimToNull(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return value.trim();
    }

    private record MinerUParseResult(String batchId, String fullZipUrl) {
    }
}
