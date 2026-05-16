package com.kesf.backend.service;

import com.kesf.backend.dto.PageResultDTO;
import com.kesf.backend.dto.PaperSummaryDTO;
import com.kesf.backend.dto.UploadDocumentDTO;
import com.kesf.backend.dto.UploadProgressDTO;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

public interface DocumentService {

    /**
     * 处理文献上传入口。
     *
     * 实现类需要完成：
     * - 校验上传文件是否为 PDF；
     * - 后端重新计算 MD5 和文件大小；
     * - 按 paper_md5 查重；
     * - 未重复时写入上传解析进度表；
     * - 返回前端可直接消费的上传进度数据。
     */
    UploadProgressDTO uploadDocument(MultipartFile file, UploadDocumentDTO uploadDocument);

    PageResultDTO<PaperSummaryDTO> listDocuments(String keyword, Integer year, String venue, Integer page, Integer pageSize);

    Map<String, Object> deleteDocument(Long paperId);
}
