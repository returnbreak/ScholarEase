package com.kesf.backend.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kesf.backend.dto.DuplicatePaperDataDTO;
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
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
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

/**
 * 文献服务实现类
 * 负责处理本地 PDF 文件的上传校验、MD5 计算与防篡改比对、文献查重以及上传进度的记录。
 */
@Service
@RequiredArgsConstructor
public class DocumentServiceImpl implements DocumentService {

    // 允许上传的最大文件大小：200MB
    private static final long MAX_FILE_SIZE_BYTES = 200L * 1024L * 1024L;
    // 解析状态常量：解析中
    private static final String PARSE_STATUS_PARSING = "PARSING";

    // 文献数据库操作 Mapper
    private final PaperMapper paperMapper;

    // 文献上传解析进度服务
    private final PaperUploadParseProgressService progressService;

    // JSON 序列化工具
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 处理文献上传的主逻辑。
     * 当前阶段仅创建“解析进度”记录。
     * 真正的“文献”记录（Paper）会在后续解析确认并且文件成功持久化到 Zotero 和 MinIO 之后才被创建。
     */
    @Override
    @Transactional
    public UploadProgressDTO uploadDocument(MultipartFile file, UploadDocumentDTO uploadDocument) {
        // 1. 基础校验：检查文件是否为空、是否超大、是否是合法的 PDF 文件
        validatePdfFile(file, uploadDocument);

        // 2. 后端重新计算文件的 MD5 和实际大小
        String actualPaperMd5 = calculateMd5(file);
        long actualFileSizeBytes = file.getSize();
        
        // 3. 防篡改与完整性校验：对比客户端传来的期望值与后端实际计算的值
        validateClientMetadata(uploadDocument, actualPaperMd5, actualFileSizeBytes);

        // 4. 文献查重：根据文件的 MD5 值去数据库查询是否已经上传过完全相同内容的文献
        PaperEntity duplicatePaper = paperMapper.selectOne(new LambdaQueryWrapper<PaperEntity>()
                .eq(PaperEntity::getPaperMd5, actualPaperMd5)
                .last("LIMIT 1"));
                
        if (duplicatePaper != null) {
            // 如果已存在，抛出重复异常，并将已存在文献的摘要信息放入异常的 data 属性中返回给前端展示
            throw new BusinessException(
                    ErrorCode.DUPLICATE_PAPER,
                    ErrorCode.DUPLICATE_PAPER.getMessage(),
                    new DuplicatePaperDataDTO("PDF_MD5_MATCHED", toSummary(duplicatePaper))
            );
        }

        // 5. 校验通过且未重复，记录此文件的上传与解析进度
        progressService.recordUploadProgress(uploadDocument);
        
        // 6. 返回进度信息给前端
        return toUploadProgress(uploadDocument, actualPaperMd5, actualFileSizeBytes);
    }

    /**
     * 校验上传的文件对象是否符合 PDF 文件的要求
     */
    private void validatePdfFile(MultipartFile file, UploadDocumentDTO uploadDocument) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_FILE_TYPE);
        }
        if (file.getSize() > MAX_FILE_SIZE_BYTES) {
            throw new BusinessException(ErrorCode.FILE_TOO_LARGE);
        }

        String fileName = uploadDocument == null ? null : uploadDocument.getFileName();
        String contentType = file.getContentType();
        
        // 校验文件名后缀（如果存在）以及文件的 Content-Type
        boolean pdfName = StringUtils.hasText(fileName) && fileName.toLowerCase(Locale.ROOT).endsWith(".pdf");
        boolean pdfContentType = "application/pdf".equalsIgnoreCase(contentType);
        if (!pdfName || !pdfContentType) {
            throw new BusinessException(ErrorCode.INVALID_FILE_TYPE);
        }
    }

    /**
     * 获取文件输入流，计算并返回统一小写格式的 MD5 字符串
     */
    private String calculateMd5(MultipartFile file) {
        try {
            return Md5Utils.md5Hex(file.getInputStream()).toLowerCase(Locale.ROOT);
        } catch (IOException exception) {
            throw new BusinessException(ErrorCode.UPLOAD_FAILED, "Failed to read uploaded file");
        }
    }

    /**
     * 校验客户端传来的元数据（MD5、文件大小等）是否与后端实际计算的结果一致
     */
    private void validateClientMetadata(UploadDocumentDTO uploadDocument, String actualPaperMd5, long actualFileSizeBytes) {
        // 确保客户端传递了必要的元数据参数
        if (uploadDocument == null
                || !StringUtils.hasText(uploadDocument.getPaperMd5())
                || uploadDocument.getFileSizeBytes() == null
                || uploadDocument.getSubmissionTime() == null) {
            throw metadataMismatch(uploadDocument, actualPaperMd5, actualFileSizeBytes);
        }

        // 统一转为小写对比
        String expectedPaperMd5 = uploadDocument.getPaperMd5().trim().toLowerCase(Locale.ROOT);
        Long expectedFileSizeBytes = uploadDocument.getFileSizeBytes();
        
        // 如果 MD5 或文件大小不匹配，说明文件可能在传输中途损坏，或者遭受了篡改
        if (!actualPaperMd5.equals(expectedPaperMd5) || actualFileSizeBytes != expectedFileSizeBytes) {
            throw metadataMismatch(uploadDocument, actualPaperMd5, actualFileSizeBytes);
        }
    }

    /**
     * 辅助方法：构建一个元数据不匹配的异常，附带实际值与期望值以便前端或日志排查
     */
    private BusinessException metadataMismatch(UploadDocumentDTO uploadDocument, String actualPaperMd5, long actualFileSizeBytes) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("expectedPaperMd5", uploadDocument == null ? null : uploadDocument.getPaperMd5());
        data.put("actualPaperMd5", actualPaperMd5);
        data.put("expectedFileSizeBytes", uploadDocument == null ? null : uploadDocument.getFileSizeBytes());
        data.put("actualFileSizeBytes", actualFileSizeBytes);
        return new BusinessException(ErrorCode.FILE_METADATA_MISMATCH, ErrorCode.FILE_METADATA_MISMATCH.getMessage(), data);
    }

    /**
     * 构造返回给前端的上传解析进度 DTO
     */
    private UploadProgressDTO toUploadProgress(UploadDocumentDTO uploadDocument, String actualPaperMd5, long actualFileSizeBytes) {
        UploadProgressDTO progress = new UploadProgressDTO();
        progress.setTraceId(uploadDocument.getTraceId());
        progress.setPaperMd5(actualPaperMd5);
        progress.setFileName(safeFileName(uploadDocument.getFileName()));
        progress.setFileSizeBytes(actualFileSizeBytes);
        progress.setSubmissionTime(uploadDocument.getSubmissionTime());
        progress.setParseStatus(PARSE_STATUS_PARSING);
        return progress;
    }

    /**
     * 将数据库中的 PaperEntity 实体对象转换为轻量级的文献摘要 DTO
     * 用于给查重拦截提示时提供已有文献的信息
     */
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

    /**
     * 将 JSON 数组字符串解析为 List<String>
     */
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

    /**
     * 将实体类中的 LocalDateTime 转换为带东八区偏移的 OffsetDateTime
     */
    private OffsetDateTime toOffsetDateTime(PaperEntity paper) {
        if (paper.getUploadTime() == null) {
            return null;
        }
        return paper.getUploadTime().atOffset(ZoneOffset.ofHours(8));
    }

    /**
     * 拼接并返回文献的存储路径
     */
    private String storageLocation(PaperEntity paper) {
        if (paper.getPaperId() == null) {
            return "";
        }
        return "papers/" + paper.getPaperId() + "/original/";
    }

    /**
     * 获取安全的文件名（剥离掉可能的目录路径部分，防止目录穿越）
     */
    private String safeFileName(String fileName) {
        String safeName = StringUtils.getFilename(fileName);
        return StringUtils.hasText(safeName) ? safeName : fileName;
    }
}
