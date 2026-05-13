package com.kesf.backend.exception;

import com.kesf.backend.dto.ApiResponseDTO;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTests {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void businessExceptionReturnsApiResponseWithClientTraceIdAndData() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/documents/upload");
        request.addHeader("X-Trace-Id", " upload-trace-001 ");
        Map<String, Object> data = Map.of("expectedPaperMd5", "front-md5", "actualPaperMd5", "backend-md5");
        BusinessException exception = new BusinessException(
                ErrorCode.FILE_METADATA_MISMATCH,
                ErrorCode.FILE_METADATA_MISMATCH.getMessage(),
                data
        );

        ResponseEntity<ApiResponseDTO<Object>> response = handler.handleBusinessException(exception, request);

        assertThat(response.getStatusCode()).isEqualTo(ErrorCode.FILE_METADATA_MISMATCH.getHttpStatus());
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCode()).isEqualTo("FILE_METADATA_MISMATCH");
        assertThat(response.getBody().getMessage()).isEqualTo(ErrorCode.FILE_METADATA_MISMATCH.getMessage());
        assertThat(response.getBody().getData()).isEqualTo(data);
        assertThat(response.getBody().getTraceId()).isEqualTo(" upload-trace-001 ");
        assertThat(response.getBody().getTimestamp()).isNotNull();
    }

    @Test
    void unexpectedExceptionReturnsApiResponseWithClientTraceId() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/documents/upload");
        request.addParameter("traceId", "upload-trace-002");

        ResponseEntity<ApiResponseDTO<Object>> response = handler.handleUnexpectedException(
                new IllegalStateException("database connection lost"),
                request
        );

        assertThat(response.getStatusCode()).isEqualTo(ErrorCode.INTERNAL_ERROR.getHttpStatus());
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCode()).isEqualTo("INTERNAL_ERROR");
        assertThat(response.getBody().getMessage()).isEqualTo(ErrorCode.INTERNAL_ERROR.getMessage());
        assertThat(response.getBody().getData()).isNull();
        assertThat(response.getBody().getTraceId()).isEqualTo("upload-trace-002");
        assertThat(response.getBody().getTimestamp()).isNotNull();
    }
}
