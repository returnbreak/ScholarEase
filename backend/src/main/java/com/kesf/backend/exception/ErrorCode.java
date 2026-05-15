package com.kesf.backend.exception;

import org.springframework.http.HttpStatus;

public enum ErrorCode {

    /**
     * 上传文件不是 PDF，或文件名 / MIME 类型不满足当前 MVP 约束。
     */
    INVALID_FILE_TYPE(HttpStatus.BAD_REQUEST, "File is not a PDF"),

    /**
     * 前端提交的 paperMd5 或 fileSizeBytes 与后端复核结果不一致。
     */
    FILE_METADATA_MISMATCH(HttpStatus.BAD_REQUEST, "File MD5 or size verification failed"),

    /**
     * 单文件大小超过后端限制。
     */
    FILE_TOO_LARGE(HttpStatus.PAYLOAD_TOO_LARGE, "File exceeds the size limit"),

    /**
     * paper_md5 已存在，说明同一 PDF 内容已经上传过。
     */
    DUPLICATE_PAPER(HttpStatus.CONFLICT, "Duplicate paper detected; upload cancelled"),

    /**
     * 上传流程中的兜底异常，例如后端读取 MultipartFile 输入流失败。
     */
    UPLOAD_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "Upload failed"),

    MINERU_PARSE_FAILED(HttpStatus.BAD_GATEWAY, "MinerU parse failed"),

    ZOTERO_WRITE_FAILED(HttpStatus.BAD_GATEWAY, "Zotero write failed"),

    /**
     * 未被业务异常显式捕获的服务端未知异常。
     */
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error");

    private final HttpStatus httpStatus;

    private final String message;

    ErrorCode(HttpStatus httpStatus, String message) {
        this.httpStatus = httpStatus;
        this.message = message;
    }

    public HttpStatus getHttpStatus() {
        return httpStatus;
    }

    public String getMessage() {
        return message;
    }
}
