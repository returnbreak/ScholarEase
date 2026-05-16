package com.kesf.backend.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kesf.backend.config.MinerUProperties;
import com.kesf.backend.config.MinioProperties;
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
import com.kesf.backend.service.ObjectStorageService;
import com.kesf.backend.service.PaperUploadParseProgressService;
import com.kesf.backend.service.ZoteroImportService;
import com.kesf.backend.utils.Md5Utils;
import com.kesf.backend.utils.MinerUClient;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

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
    private final ZoteroImportService zoteroImportService;
    private final ObjectStorageService objectStorageService;
    private final MinioProperties minioProperties;
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
            String traceId = uploadDocument.getTraceId();
            String fileName = safeFileName(uploadDocument.getFileName());
            byte[] pdfBytes = readFileBytes(file);
            uploadOriginalPdf(pdfBytes, traceId, fileName);
            MinerUParseResult parseResult = parseByMinerU(
                    pdfBytes,
                    traceId,
                    fileName
            );
            zoteroImportService.importParsedPaper(
                    pdfBytes,
                    fileName,
                    traceId
            );
            progressService.updateParseStatus(traceId, PARSE_STATUS_PARSED_VALUE);
            return parseResult;
        } catch (BusinessException exception) {
            progressService.updateParseStatus(uploadDocument.getTraceId(), PARSE_STATUS_FAILED_VALUE);
            throw exception;
        } catch (RuntimeException exception) {
            progressService.updateParseStatus(uploadDocument.getTraceId(), PARSE_STATUS_FAILED_VALUE);
            throw new BusinessException(ErrorCode.MINERU_PARSE_FAILED, ErrorCode.MINERU_PARSE_FAILED.getMessage());
        }
    }

    private MinerUParseResult parseByMinerU(byte[] fileBytes, String traceId, String fileName) {
        if (!minerUProperties.isEnabled()) {
            throw minerUFailed("MinerU parsing is disabled");
        }

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
                byte[] fullZipBytes = minerUClient.downloadFullZip(result.fullZipUrl());
                uploadMinerUArtifacts(fullZipBytes, traceId);
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

    private void uploadOriginalPdf(byte[] pdfBytes, String traceId, String fileName) {
        objectStorageService.putObject(
                originalPdfObjectKey(traceId, fileName),
                pdfBytes,
                "application/pdf"
        );
    }

    /**
     * 解压 MinerU 返回的 ZIP 包，并将其中的关键解析产物上传到对象存储（如 MinIO）
     *
     * @param fullZipBytes MinerU 返回的完整 ZIP 文件的字节数组
     * @param traceId      当前上传流程的追踪 ID
     */
    private void uploadMinerUArtifacts(byte[] fullZipBytes, String traceId) {
        // 标记是否成功提取到了核心文件 full.md
        boolean hasFullMarkdown = false;
        // 标记是否成功提取到了核心文件 content_list_v2.json
        boolean hasContentListV2 = false;
        
        // 使用 try-with-resources 自动关闭流，将字节数组转换为 ZIP 输入流以供读取
        try (ZipInputStream zipInputStream = new ZipInputStream(new ByteArrayInputStream(fullZipBytes))) {
            ZipEntry entry;
            // 遍历 ZIP 包中的每一个条目（文件或目录）
            while ((entry = zipInputStream.getNextEntry()) != null) {
                // 跳过目录，只处理实际文件
                if (entry.isDirectory()) {
                    continue;
                }

                // 获取经过安全校验后的相对文件路径（防止 Zip Slip 路径穿越漏洞）
                String relativeName = safeZipEntryName(entry);
                String storageName = minerUArtifactStorageName(relativeName);
                // 过滤不需要的文件，判断当前文件是否是我们需要的解析产物
                if (!StringUtils.hasText(storageName)) {
                    continue;
                }

                // 读取当前 ZIP 文件条目的完整内容为字节数组
                byte[] content = readZipEntry(zipInputStream);
                // 调用对象存储服务，将文件上传到 MinIO
                // 存储路径由 traceId 和相对路径组合而成，contentType 则根据文件后缀推断
                objectStorageService.putObject(
                        minerUArtifactObjectKey(traceId, storageName),
                        content,
                        contentType(storageName)
                );
                // 检查当前处理的文件是否为必备的核心产物，并更新标志位
                hasFullMarkdown = hasFullMarkdown || "full.md".equals(storageName);
                hasContentListV2 = hasContentListV2 || "content_list_v2.json".equals(storageName);
            }
        } catch (IOException exception) {
            // 捕获解压过程中的 IO 异常并转化为业务异常
            throw minerUFailed("MinerU full ZIP extraction failed: " + exception.getMessage());
        }

        // 校验产物完整性：如果缺失全量 Markdown 或核心内容列表 JSON，则视为解析异常/失败
        if (!hasFullMarkdown || !hasContentListV2) {
            throw minerUFailed("MinerU full ZIP missing required artifacts");
        }
    }

    private byte[] readZipEntry(ZipInputStream zipInputStream) throws IOException {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        zipInputStream.transferTo(outputStream);
        return outputStream.toByteArray();
    }

    private String safeZipEntryName(ZipEntry entry) {
        String entryName = entry.getName().replace('\\', '/');
        if ("..".equals(entryName) || entryName.startsWith("../") || entryName.contains("/../")) {
            throw minerUFailed("MinerU full ZIP contains unsafe entry: " + entry.getName());
        }
        Path normalizedPath = Path.of(entryName).normalize();
        if (normalizedPath.isAbsolute() || normalizedPath.startsWith("..")) {
            throw minerUFailed("MinerU full ZIP contains unsafe entry: " + entry.getName());
        }
        return normalizedPath.toString().replace('\\', '/');
    }

    private String minerUArtifactStorageName(String relativeName) {
        String fileName = StringUtils.getFilename(relativeName);
        if ("full.md".equals(fileName)) {
            return "full.md";
        }
        if ("content_list_v2.json".equals(fileName)
                || (StringUtils.hasText(fileName) && fileName.endsWith("_content_list_v2.json"))) {
            return "content_list_v2.json";
        }
        return null;
    }

    private String minerUArtifactObjectKey(String traceId, String relativeName) {
        String prefix = minioProperties.getStorage().getParsedPrefix()
                .replace("{traceId}", safeTraceId(traceId));
        if (!prefix.endsWith("/")) {
            prefix = prefix + "/";
        }
        return prefix + relativeName;
    }

    private String originalPdfObjectKey(String traceId, String fileName) {
        String prefix = minioProperties.getStorage().getOriginalPrefix()
                .replace("{traceId}", safeTraceId(traceId));
        if (!prefix.endsWith("/")) {
            prefix = prefix + "/";
        }
        return prefix + safeFileName(fileName);
    }

    private String contentType(String relativeName) {
        String lowerName = relativeName.toLowerCase(Locale.ROOT);
        if (lowerName.endsWith(".md")) {
            return "text/markdown; charset=utf-8";
        }
        if (lowerName.endsWith(".json")) {
            return "application/json";
        }
        if (lowerName.endsWith(".jpg") || lowerName.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (lowerName.endsWith(".png")) {
            return "image/png";
        }
        return "application/octet-stream";
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

    private String safeTraceId(String traceId) {
        if (!StringUtils.hasText(traceId)) {
            return "unknown";
        }
        return traceId.replaceAll("[^A-Za-z0-9_-]", "-");
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
