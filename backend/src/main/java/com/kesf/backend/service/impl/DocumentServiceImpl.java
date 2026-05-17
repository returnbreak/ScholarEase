package com.kesf.backend.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kesf.backend.config.MinerUProperties;
import com.kesf.backend.config.MinioProperties;
import com.kesf.backend.dto.DuplicatePaperDataDTO;
import com.kesf.backend.dto.PageResultDTO;
import com.kesf.backend.dto.PaperDetailDTO;
import com.kesf.backend.dto.PaperSummaryDTO;
import com.kesf.backend.dto.UploadDocumentDTO;
import com.kesf.backend.dto.UploadProgressDTO;
import com.kesf.backend.entity.PaperEntity;
import com.kesf.backend.entity.PaperLocationsEntity;
import com.kesf.backend.exception.BusinessException;
import com.kesf.backend.exception.ErrorCode;
import com.kesf.backend.kafka.PaperVectorIndexTask;
import com.kesf.backend.mapper.PaperLocationsMapper;
import com.kesf.backend.mapper.PaperMapper;
import com.kesf.backend.service.DocumentService;
import com.kesf.backend.service.ObjectStorageService;
import com.kesf.backend.service.PaperUploadParseProgressService;
import com.kesf.backend.service.PaperVectorSearchIndexService;
import com.kesf.backend.service.PaperVectorIndexProducer;
import com.kesf.backend.service.ZoteroImportService;
import com.kesf.backend.utils.Md5Utils;
import com.kesf.backend.utils.MinerUClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Slf4j
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
    /** 默认的文本向量化模型版本，写入 Kafka 任务供消费者端调用 Embedding API 时使用 */
    private static final String DEFAULT_EMBEDDING_MODEL_VERSION = "text-embedding-v4";

    private final PaperMapper paperMapper;
    private final PaperLocationsMapper paperLocationsMapper;
    private final PaperUploadParseProgressService progressService;
    private final MinerUClient minerUClient;
    private final MinerUProperties minerUProperties;
    private final ZoteroImportService zoteroImportService;
    private final ObjectStorageService objectStorageService;
    private final MinioProperties minioProperties;
    /** 论文向量索引任务生产者，在论文入库前将向量化任务发送到 Kafka，实现异步解耦 */
    private final PaperVectorIndexProducer paperVectorIndexProducer;
    /** 论文向量搜索索引服务，用于删除论文时直接清理 Elasticsearch 中的向量文档 */
    private final PaperVectorSearchIndexService paperVectorSearchIndexService;
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
                    new DuplicatePaperDataDTO("PDF_MD5_MATCHED",
                            toSummary(duplicatePaper, Map.of(actualPaperMd5, "")))
            );
        }

        progressService.recordUploadProgress(uploadDocument);
        MinerUParseResult parseResult = parseWithMinerU(file, uploadDocument, actualPaperMd5, actualFileSizeBytes);

        return toUploadProgress(uploadDocument, actualPaperMd5, actualFileSizeBytes, parseResult);
    }

    /**
     * 分页查询文献列表，支持根据关键字、年份、发表来源等多条件进行动态过滤。
     *
     * @param keyword  搜索关键字（模糊匹配标题、文件名、作者或 DOI）
     * @param year     发表年份（精确匹配）
     * @param venue    发表来源如期刊/会议名（模糊匹配）
     * @param page     当前页码
     * @param pageSize 每页条数
     * @return 包含文献概览列表、分页信息以及是否有下一页的封装对象
     */
    @Override
    public PageResultDTO<PaperSummaryDTO> listDocuments(
            String keyword,
            Integer year,
            String venue,
            Integer page,
            Integer pageSize
    ) {
        // 1. 处理分页参数的安全默认值，防止空指针或非法的极值拖垮数据库
        int safePage = page == null || page < 1 ? 1 : page;
        int safePageSize = pageSize == null || pageSize < 1 ? 20 : Math.min(pageSize, 100);

        // 2. 初始化 MyBatis-Plus 的 Lambda 查询条件构造器
        LambdaQueryWrapper<PaperEntity> queryWrapper = new LambdaQueryWrapper<>();

        // 3. 动态构建关键字匹配条件：只要标题、文件名、作者列表(JSON字符串)或 DOI 中包含关键字即视为命中
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

        // 4. 动态构建年份精确匹配条件
        if (year != null) {
            queryWrapper.eq(PaperEntity::getYear, year);
        }

        // 5. 动态构建来源模糊匹配条件
        String trimmedVenue = trimToNull(venue);
        if (trimmedVenue != null) {
            queryWrapper.like(PaperEntity::getVenue, trimmedVenue);
        }

        // 6. 设定默认排序规则：优先按上传时间倒序排列，若时间相同则按主键 ID 倒序（保证分页时的顺序稳定性）
        queryWrapper.orderByDesc(PaperEntity::getUploadTime).orderByDesc(PaperEntity::getPaperId);

        // 7. 执行底层的分页 SQL 查询
        Page<PaperEntity> resultPage = paperMapper.selectPage(new Page<>(safePage, safePageSize), queryWrapper);

        // 8. 批量查询文献位置表，构建 paper_md5 -> zotero_collection_name 映射
        List<PaperEntity> records = resultPage.getRecords();
        Map<String, String> md5ToCollectionName = buildCollectionNameMap(records);

        List<PaperSummaryDTO> items = records.stream()
                .map(paper -> toSummary(paper, md5ToCollectionName))
                .toList();

        // 9. 组装并返回自定义的分页结果对象，通过对比当前页和总页数来确定 hasNext（是否有下一页）标志
        return new PageResultDTO<>(
                items,
                safePage,
                safePageSize,
                resultPage.getTotal(),
                resultPage.getCurrent() < resultPage.getPages()
        );
    }

    @Override
    public PaperDetailDTO getDocument(Long paperId) {
        PaperEntity paper = paperMapper.selectById(paperId);
        if (paper == null) {
            throw new BusinessException(ErrorCode.PAPER_NOT_FOUND);
        }
        return toDetail(paper);
    }

    @Override
    @Transactional
    public Map<String, Object> deleteDocument(Long paperId) {
        PaperEntity paper = paperMapper.selectById(paperId);
        if (paper == null) {
            throw new BusinessException(ErrorCode.PAPER_NOT_FOUND);
        }

        PaperLocationsEntity locations = paperLocationsMapper.selectOne(
                new LambdaQueryWrapper<PaperLocationsEntity>()
                        .eq(PaperLocationsEntity::getPaperMd5, paper.getPaperMd5()));

        if (locations != null) {
            if (StringUtils.hasText(locations.getMinioOriginalKey())) {
                objectStorageService.deleteObject(locations.getMinioOriginalKey());
                log.info("Deleted MinIO original PDF: {}", locations.getMinioOriginalKey());
            }
            if (StringUtils.hasText(locations.getMinioParsedPrefix())) {
                objectStorageService.deleteObjectsByPrefix(locations.getMinioParsedPrefix());
                log.info("Deleted MinIO parsed artifacts under: {}", locations.getMinioParsedPrefix());
            }
            String tracePrefix = extractTracePrefix(locations);
            if (tracePrefix != null) {
                objectStorageService.deleteObjectsByPrefix(tracePrefix);
                log.info("Deleted MinIO trace level: {}", tracePrefix);
            }
        }

        if (locations != null && StringUtils.hasText(locations.getZoteroItemKey())) {
            zoteroImportService.deleteItem(locations.getZoteroItemKey());
            log.info("Deleted Zotero item: {}", locations.getZoteroItemKey());
        }

        paperVectorSearchIndexService.deleteByPaperMd5(paper.getPaperMd5());
        log.info("Deleted Elasticsearch vector documents for paperMd5: {}", paper.getPaperMd5());
        // 最后删除数据库记录，确保 ES 删除失败时不会留下孤儿向量文档
        if (locations != null) {
            paperLocationsMapper.deleteById(locations.getId());
        }
        paperMapper.deleteById(paperId);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("deleted", true);
        result.put("paperId", paperId);
        return result;
    }

    /**
     * 执行 MinerU 解析流水线：上传原始 PDF 到 MinIO → 提交 MinerU 解析 → 轮询等待完成 →
     * 下载并解压完整 ZIP 产物 → 导入 Zotero 提取元数据 → 组装实体入库 → 发送向量化任务到 Kafka。
     * <p>
     * 该方法是整个文献上传流程的核心编排方法。每个步骤的失败都有明确的错误码和状态更新，
     * 确保前端可以通过 traceId 轮询到最新的解析进度。
     *
     * @param file                 前端上传的原始 PDF 文件
     * @param uploadDocument       前端提交的元数据（含 traceId、预期 MD5、文件大小等）
     * @param actualPaperMd5       后端实际计算的 PDF MD5（服务端权威值）
     * @param actualFileSizeBytes  后端实际获取的文件大小（服务端权威值）
     * @return MinerU 解析结果（含 batchId 和产物下载地址）
     */
    private MinerUParseResult parseWithMinerU(
            MultipartFile file,
            UploadDocumentDTO uploadDocument,
            String actualPaperMd5,
            long actualFileSizeBytes
    ) {
        try {
        String traceId = uploadDocument.getTraceId();
            String fileName = safeFileName(uploadDocument.getFileName());

            byte[] pdfBytes = readFileBytes(file);

            // 1. 将原始 PDF 上传到 MinIO 对象存储
            uploadOriginalPdf(pdfBytes, traceId, fileName);

            // 2. 提交 MinerU 解析任务并轮询等待完成
            MinerUParseResult parseResult = parseByMinerU(
                    pdfBytes,
                    traceId,
                    fileName
            );

            // 3. 下载 MinerU 解析产物的完整 ZIP 包，解压后上传到 MinIO
            //    ZIP 中包含 full.md（完整 Markdown）和 content_list_v2.json（结构化内容块列表）等文件
            //    这些文件是后续向量化的数据源
            // 4. 将 PDF 发送到 Zotero 桌面软件，利用其元数据识别能力提取标题、作者、DOI 等信息

            // 5. 调用 Zotero 导入服务，利用其元数据识别能力从 PDF 提取标题、作者、DOI 等结构化信息
            ZoteroImportService.ZoteroImportResult zoteroResult = zoteroImportService.importParsedPaper(
                    pdfBytes,
                    fileName,
                    traceId
            );

            // 6. 将提取到的 Zotero 元数据与基础文件信息组装为实体类，并保存到数据库
            PaperEntity paperEntity = toPaperEntity(
                    uploadDocument,
                    actualPaperMd5,
                    actualFileSizeBytes,
                    fileName,
                    zoteroResult.metadata()
            );
            // 6a. 在数据库写入前，先将向量化任务发送到 Kafka（异步解耦，失败不影响主流程）
            paperVectorIndexProducer.send(toPaperVectorIndexTask(
                    uploadDocument,
                    actualPaperMd5,
                    actualFileSizeBytes,
                    fileName,
                    zoteroResult.metadata()
            ));
            paperMapper.insert(paperEntity);

            // 7. 写入文献位置信息（MinIO + Zotero）
            PaperLocationsEntity locations = new PaperLocationsEntity();
            locations.setPaperId(paperEntity.getPaperId());
            locations.setPaperMd5(actualPaperMd5);
            locations.setFileName(fileName);
            locations.setSubmissionTime(uploadDocument.getSubmissionTime().toLocalDateTime());
            locations.setMinioBucket(minioProperties.getBucketName());
            locations.setMinioOriginalKey(originalPdfObjectKey(traceId, fileName));
            locations.setMinioParsedPrefix(minioProperties.getStorage().getParsedPrefix()
                    .replace("{traceId}", safeTraceId(traceId)));
            locations.setZoteroItemKey(zoteroResult.parentItemKey());
            locations.setZoteroCollectionName(zoteroResult.collectionName());
            paperLocationsMapper.insert(locations);

            progressService.updateParseStatus(traceId, PARSE_STATUS_PARSED_VALUE);

            return parseResult;
        } catch (BusinessException exception) {
            // 业务异常：保留原始错误码（如文件太大、重复上传等），仅更新解析状态为失败
            progressService.updateParseStatus(uploadDocument.getTraceId(), PARSE_STATUS_FAILED_VALUE);
            throw exception;
        } catch (RuntimeException exception) {
            // 非预期运行时异常：统一包装为 MINERU_PARSE_FAILED 错误码
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


    private void uploadMinerUArtifacts(byte[] fullZipBytes, String traceId) {
        // 标记是否已找到 full.md（MinerU 输出的完整 Markdown 文件）
        boolean hasFullMarkdown = false;
        // 标记是否已找到 content_list_v2.json（MinerU 输出的结构化内容块列表）
        boolean hasContentListV2 = false;

        try (ZipInputStream zipInputStream = new ZipInputStream(new ByteArrayInputStream(fullZipBytes))) {
            ZipEntry entry;
            // 遍历 ZIP 包中的每一个条目（文件或目录），按需上传到 MinIO
            while ((entry = zipInputStream.getNextEntry()) != null) {
                // 跳过目录条目，只处理实际文件
                if (entry.isDirectory()) {
                    continue;
                }

                String relativeName = safeZipEntryName(entry);
                String storageName = minerUArtifactStorageName(relativeName);
                // 跳过不需要存储的文件（非 full.md 且非 content_list_v2.json）
                if (!StringUtils.hasText(storageName)) {
                    continue;
                }

                // 读取 ZIP 条目内容到内存字节数组
                byte[] content = readZipEntry(zipInputStream);
                // 上传到 MinIO，对象键由 traceId 和文件名组成；
                // Content-Type 根据文件扩展名自动推断（.md → text/markdown, .json → application/json）
                objectStorageService.putObject(
                        minerUArtifactObjectKey(traceId, storageName),
                        content,
                        contentType(storageName)
                );
                hasFullMarkdown = hasFullMarkdown || "full.md".equals(storageName);
                hasContentListV2 = hasContentListV2 || "content_list_v2.json".equals(storageName);
            }
        } catch (IOException exception) {
            // ZIP 读取或解压过程中的 IO 异常统一包装为业务异常
            throw minerUFailed("MinerU full ZIP extraction failed: " + exception.getMessage());
        }

        // 完整性校验：必须同时包含 full.md 和 content_list_v2.json 两个关键文件
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
        // 防止 ZIP 路径穿越攻击（如 ../../etc/passwd）
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

    private PaperEntity toPaperEntity(
            UploadDocumentDTO uploadDocument,
            String actualPaperMd5,
            long actualFileSizeBytes,
            String fileName,
            ZoteroImportService.ZoteroPaperMetadata metadata
    ) {
        PaperEntity paper = new PaperEntity();
        paper.setPaperMd5(actualPaperMd5);
        paper.setFileName(fileName);
        paper.setFileSizeBytes(actualFileSizeBytes);
        paper.setTitle(StringUtils.hasText(metadata.title()) ? metadata.title() : titleFromFileName(fileName));
        paper.setAuthorsJson(writeJsonArray(metadata.authors()));
        paper.setKeywordsJson(writeJsonArray(metadata.keywords()));
        paper.setLanguage(StringUtils.hasText(metadata.language()) ? metadata.language() : "en");
        paper.setYear(metadata.year());
        paper.setVenue(metadata.venue());
        paper.setDoi(metadata.doi());
        paper.setUploadTime(uploadDocument.getSubmissionTime().toLocalDateTime());
        return paper;
    }

    /**
     * 构建论文向量索引任务对象。
     * <p>
     * 将上传信息、Zotero 识别元数据、MinIO 文件路径组装为 {@link PaperVectorIndexTask}，
     * 供 Kafka 生产者发送到向量化队列。消费者端将从 MinIO 读取 MinerU 解析产物并执行向量化。
     * </p>
     *
     * @param uploadDocument      前端上传请求中的元数据
     * @param actualPaperMd5      后端实际计算的 PDF MD5
     * @param actualFileSizeBytes 后端实际获取的文件大小
     * @param fileName            原始文件名
     * @param metadata            Zotero 识别的论文元数据（标题、作者、年份等）
     * @return 填充完整的向量索引任务对象
     */
    private PaperVectorIndexTask toPaperVectorIndexTask(
            UploadDocumentDTO uploadDocument,
            String actualPaperMd5,
            long actualFileSizeBytes,
            String fileName,
            ZoteroImportService.ZoteroPaperMetadata metadata
    ) {
        String traceId = uploadDocument.getTraceId();
        PaperVectorIndexTask task = new PaperVectorIndexTask();
        task.setTaskId(traceId + ":" + actualPaperMd5); // 任务 ID = traceId:md5，全局唯一
        task.setTraceId(traceId);
        task.setPaperMd5(actualPaperMd5);
        task.setFileName(fileName);
        task.setFileSizeBytes(actualFileSizeBytes);
        task.setSubmissionTime(uploadDocument.getSubmissionTime());
        task.setMinioBucket(minioProperties.getBucketName()); // MinIO 桶名（如 "literatures"）
        // MinerU 解析产物的两个关键文件路径
        task.setContentListObjectKey(minerUArtifactObjectKey(traceId, "content_list_v2.json"));
        task.setFullMarkdownObjectKey(minerUArtifactObjectKey(traceId, "full.md"));
        // 标题：优先 Zotero 识别的标题，回退到文件名推断（去 .pdf 后缀）
        task.setTitle(StringUtils.hasText(metadata.title()) ? metadata.title() : titleFromFileName(fileName));
        // 作者/关键词：Zotero 未识别到则为空列表
        task.setAuthors(metadata.authors() == null ? List.of() : metadata.authors());
        task.setKeywords(metadata.keywords() == null ? List.of() : metadata.keywords());
        // 语言：Zotero 未识别到则默认 "en"
        task.setLanguage(StringUtils.hasText(metadata.language()) ? metadata.language() : "en");
        task.setYear(metadata.year());
        task.setVenue(metadata.venue());
        task.setDoi(metadata.doi());
        task.setModelVersion(DEFAULT_EMBEDDING_MODEL_VERSION); // Embedding 模型版本号
        return task;
    }

    private String writeJsonArray(List<String> values) {
        try {
            return objectMapper.writeValueAsString(values == null ? List.of() : values);
        } catch (IOException exception) {
            throw new BusinessException(ErrorCode.UPLOAD_FAILED, "Failed to serialize paper metadata");
        }
    }

    private String titleFromFileName(String fileName) {
        if (!StringUtils.hasText(fileName)) {
            return "Uploaded PDF";
        }
        return fileName.toLowerCase(Locale.ROOT).endsWith(".pdf")
                ? fileName.substring(0, fileName.length() - 4)
                : fileName;
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

    private PaperSummaryDTO toSummary(PaperEntity paper, Map<String, String> md5ToCollectionName) {
        PaperSummaryDTO summary = new PaperSummaryDTO();
        summary.setPaperId(paper.getPaperId());
        summary.setPaperMd5(paper.getPaperMd5());
        summary.setFileName(paper.getFileName());
        summary.setFileSizeBytes(paper.getFileSizeBytes());
        summary.setTitle(paper.getTitle());
        List<String> authors = parseJsonArray(paper.getAuthorsJson());
        summary.setAuthors(authors);
        summary.setAuthorText(String.join(", ", authors));
        summary.setStorageLocation(md5ToCollectionName.getOrDefault(paper.getPaperMd5(), ""));
        summary.setUploadTime(toOffsetDateTime(paper));
        summary.setYear(paper.getYear());
        summary.setVenue(paper.getVenue());
        summary.setDoi(paper.getDoi());
        summary.setKeywords(parseJsonArray(paper.getKeywordsJson()));
        return summary;
    }

    private PaperDetailDTO toDetail(PaperEntity paper) {
        PaperDetailDTO detail = new PaperDetailDTO();
        detail.setPaperId(paper.getPaperId());
        detail.setPaperMd5(paper.getPaperMd5());
        detail.setFileName(paper.getFileName());
        detail.setFileSizeBytes(paper.getFileSizeBytes());
        detail.setTitle(paper.getTitle());
        List<String> authors = parseJsonArray(paper.getAuthorsJson());
        detail.setAuthors(authors);
        detail.setAuthorText(String.join(", ", authors));
        detail.setKeywords(parseJsonArray(paper.getKeywordsJson()));
        detail.setLanguage(paper.getLanguage());
        detail.setStorageLocation("");
        detail.setUploadTime(toOffsetDateTime(paper));
        detail.setYear(paper.getYear());
        detail.setVenue(paper.getVenue());
        detail.setDoi(paper.getDoi());
        return detail;
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

    private String extractTracePrefix(PaperLocationsEntity locations) {
        String key = locations.getMinioParsedPrefix();
        if (!StringUtils.hasText(key)) {
            key = locations.getMinioOriginalKey();
        }
        if (!StringUtils.hasText(key)) {
            return null;
        }
        int secondSlash = key.indexOf('/', key.indexOf('/') + 1);
        if (secondSlash < 0) {
            return null;
        }
        return key.substring(0, secondSlash + 1);
    }

    private Map<String, String> buildCollectionNameMap(List<PaperEntity> papers) {
        if (papers.isEmpty()) {
            return Collections.emptyMap();
        }
        Set<String> md5s = papers.stream()
                .map(PaperEntity::getPaperMd5)
                .collect(Collectors.toSet());
        List<PaperLocationsEntity> locations = paperLocationsMapper.selectList(
                new LambdaQueryWrapper<PaperLocationsEntity>()
                        .in(PaperLocationsEntity::getPaperMd5, md5s));
        return locations.stream()
                .collect(Collectors.toMap(
                        PaperLocationsEntity::getPaperMd5,
                        loc -> StringUtils.hasText(loc.getZoteroCollectionName())
                                ? loc.getZoteroCollectionName()
                                : "",
                        (a, b) -> a));
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
