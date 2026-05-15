package com.kesf.backend.exception;

import com.kesf.backend.dto.ApiResponseDTO;
import com.kesf.backend.dto.UploadDocumentDTO;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理器。
 *
 * 使用 @RestControllerAdvice 注解，使其能够捕获所有 @RestController 抛出的异常。
 * 它的核心职责是：
 * 1. 捕获不同类型的异常（业务异常、未知异常）。
 * 2. 将异常信息统一包装成 ApiResponseDTO 格式。
 * 3. 根据异常类型返回合适的 HTTP 状态码。
 * 4. 记录详细的异常日志，便于问题排查。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 从 HTTP 请求参数中获取 traceId 的键名。
     */
    private static final String TRACE_ID_PARAMETER = "traceId";

    /**
     * 从 HTTP 请求头中获取 traceId 的键名。
     */
    private static final String TRACE_ID_HEADER = "X-Trace-Id";

    /**
     * 处理自定义的业务异常（BusinessException）。
     *
     * @param exception 捕获到的业务异常实例。
     * @param request   当前的 HTTP 请求对象，用于获取请求相关信息（如 URI, Method, TraceId）。
     * @return 封装了错误信息的 ResponseEntity 对象，包含统一的响应体和业务决定的 HTTP 状态码。
     */
    @ExceptionHandler(BusinessException.class)
    public ApiResponseDTO<Object> handleBusinessException(
            BusinessException exception,
            HttpServletRequest request
    ) {
        ErrorCode errorCode = exception.getErrorCode();
        // 从请求中解析 traceId，如果常规方式失败，会尝试从异常数据中提取
        String traceId = resolveTraceId(request, exception);

        // 构建标准失败响应体
        ApiResponseDTO<Object> response = ApiResponseDTO.fail(
                errorCode.name(),
                exception.getMessage(),
                exception.getData(),
                traceId
        );

        // 记录警告级别的日志，因为业务异常是预期内的，但需要关注
        log.warn(
                "Business exception handled: traceId={}, code={}, method={}, uri={}, message={}, data={}",
                response.getTraceId(),
                errorCode.name(),
                request.getMethod(),
                request.getRequestURI(),
                exception.getMessage(),
                exception.getData()
        );

        return response;
    }

    /**
     * 处理所有未被特定处理器捕获的未知异常（Exception）。
     * 这是最终的兜底异常处理，防止任何未处理的异常导致服务崩溃或返回不友好的错误页面。
     *
     * @param exception 捕获到的未知异常实例。
     * @param request   当前的 HTTP 请求对象。
     * @return 封装了通用内部错误信息的 ResponseEntity 对象。
     */
    @ExceptionHandler(Exception.class)
    public ApiResponseDTO<Object> handleUnexpectedException(
            Exception exception,
            HttpServletRequest request
    ) {
        ErrorCode errorCode = ErrorCode.INTERNAL_ERROR;
        ApiResponseDTO<Object> response = ApiResponseDTO.fail(
                errorCode.name(),
                errorCode.getMessage(),
                null,
                resolveTraceId(request, null) // 尝试解析 traceId
        );

        // 记录错误级别的日志，并包含完整的异常堆栈信息，便于开发人员定位问题
        log.error(
                "Unexpected exception handled: traceId={}, method={}, uri={}, message={}",
                response.getTraceId(),
                request.getMethod(),
                request.getRequestURI(),
                exception.getMessage(),
                exception
        );

        // 返回 500 内部服务器错误状态
        return response;
    }

    /**
     * 从 HTTP 请求中解析 Trace ID。
     *
     * 这是一个为了保证日志和响应中 traceId 完整性的辅助方法。
     * 它会按照“请求头 > 请求参数 > 业务异常数据”的顺序进行查找，以提高找到 traceId 的概率。
     *
     * @param request   HTTP 请求对象。
     * @param exception 可选的业务异常，可能在其 data 字段中携带了包含 traceId 的 DTO。
     * @return 解析到的 Trace ID，如果都找不到则返回 null。
     */
    private String resolveTraceId(HttpServletRequest request, BusinessException exception) {
        // 1. 优先从请求头 'X-Trace-Id' 中获取
        String traceId = request.getHeader(TRACE_ID_HEADER);
        if (StringUtils.hasText(traceId)) {
            return traceId;
        }

        // 2. 其次从请求参数 'traceId' 中获取
        //    对于 multipart/form-data 请求，非文件部分也会被当作参数。
        try {
            traceId = request.getParameter(TRACE_ID_PARAMETER);
            if (StringUtils.hasText(traceId)) {
                return traceId;
            }
        } catch (RuntimeException e) {
            // 在某些情况下（例如，流已关闭或解析错误），调用 getParameter 可能会失败。
            // 记录一个 debug 日志，并继续尝试其他方式。
            log.debug("Failed to resolve traceId from request parameter", e);
        }

        // 3. 作为最后手段，尝试从业务异常的 data 字段中提取。
        //    这是一种防御性编程，假设业务逻辑可能将包含 traceId 的 DTO 放入异常数据中。
        if (exception != null && exception.getData() instanceof UploadDocumentDTO) {
            traceId = ((UploadDocumentDTO) exception.getData()).getTraceId();
            if (StringUtils.hasText(traceId)) {
                return traceId;
            }
        }

        // 4. 如果都找不到，返回 null
        return null;
    }
}
