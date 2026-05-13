package com.kesf.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ApiResponseDTO<T> {

    private String code;

    private String message;

    private T data;

    private String traceId;

    private OffsetDateTime timestamp;

    public static <T> ApiResponseDTO<T> success(T data) {
        return new ApiResponseDTO<>("SUCCESS", "ok", data, newTraceId(), OffsetDateTime.now());
    }

    public static <T> ApiResponseDTO<T> success(String message, T data) {
        return new ApiResponseDTO<>("SUCCESS", message, data, newTraceId(), OffsetDateTime.now());
    }

    /**
     * 使用调用方指定的 traceId 构造成功响应。
     *
     * 文献上传接口的 traceId 由前端生成，表示“本次上传请求”的追踪 ID；
     * 响应里沿用同一个 traceId，前端和后端进度表才能按同一条链路排查。
     * 如果调用方没有传 traceId，则退回到后端生成，避免响应字段为空。
     */
    public static <T> ApiResponseDTO<T> success(String message, T data, String traceId) {
        String responseTraceId = traceId == null || traceId.isBlank() ? newTraceId() : traceId;
        return new ApiResponseDTO<>("SUCCESS", message, data, responseTraceId, OffsetDateTime.now());
    }

    public static <T> ApiResponseDTO<T> failed(String code, String message, T data) {
        return fail(code, message, data);
    }

    public static <T> ApiResponseDTO<T> failed(String code, String message, T data, String traceId) {
        return fail(code, message, data, traceId);
    }

    public static <T> ApiResponseDTO<T> fail(String code, String message, T data) {
        return new ApiResponseDTO<>(code, message, data, newTraceId(), OffsetDateTime.now());
    }

    public static <T> ApiResponseDTO<T> fail(String code, String message, T data, String traceId) {
        String responseTraceId = traceId == null || traceId.isBlank() ? newTraceId() : traceId;
        return new ApiResponseDTO<>(code, message, data, responseTraceId, OffsetDateTime.now());
    }

    private static String newTraceId() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
