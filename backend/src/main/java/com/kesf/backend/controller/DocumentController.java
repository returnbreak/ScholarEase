package com.kesf.backend.controller;

import com.kesf.backend.dto.ApiResponseDTO;
import com.kesf.backend.dto.PageResultDTO;
import com.kesf.backend.dto.PaperSummaryDTO;
import com.kesf.backend.dto.UploadDocumentDTO;
import com.kesf.backend.dto.UploadProgressDTO;
import com.kesf.backend.service.DocumentService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/documents")
public class DocumentController {

    /**
     * 文献上传相关业务服务。
     *
     * Controller 只负责 HTTP 层参数接收和统一响应包装；
     * 文件校验、MD5 复核、查重、落库等核心流程全部下沉到 Service，
     * 这样后续如果要接入任务队列或 MinerU 解析，也不会让接口层变重。
     */
    private final DocumentService documentService;

    @GetMapping
    public ApiResponseDTO<PageResultDTO<PaperSummaryDTO>> listDocuments(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) String venue,
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "20") Integer pageSize
    ) {
        return ApiResponseDTO.success(documentService.listDocuments(keyword, year, venue, page, pageSize));
    }

    /**
     * 上传单篇本地 PDF 文献。
     *
     * 前端通过 FormData 提交：
     * - file：PDF 文件本体；
     * - traceId/fileName/paperMd5/fileSizeBytes/submissionTime：文件元数据。
     *
     * 注意：
     * - file 使用 @RequestPart 接收，因为它是 multipart 中的文件分片；
     * - uploadDocument 使用 @ModelAttribute 接收普通表单字段，并自动绑定到 UploadDocumentDTO；
     * - 返回值继续走 ApiResponseDTO，保持前端 documents.ts 中的统一解析逻辑不变。
     */
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponseDTO<UploadProgressDTO> uploadDocument(
            @RequestPart("file") MultipartFile file,
            @ModelAttribute UploadDocumentDTO uploadDocument
    ) {
        return ApiResponseDTO.success(
                "Upload succeeded; parsing completed",
                documentService.uploadDocument(file, uploadDocument),
                uploadDocument.getTraceId()
        );
    }

    @DeleteMapping("/{paperId}")
    public ApiResponseDTO<Map<String, Object>> deleteDocument(@PathVariable Long paperId) {
        Map<String, Object> result = documentService.deleteDocument(paperId);
        return ApiResponseDTO.success("Document deleted", result);
    }
}
