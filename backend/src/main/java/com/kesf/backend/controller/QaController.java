package com.kesf.backend.controller;

import com.kesf.backend.dto.ApiResponseDTO;
import com.kesf.backend.dto.qa.QaDeleteSessionResponse;
import com.kesf.backend.dto.qa.QaSessionDetailResponse;
import com.kesf.backend.dto.qa.QaSessionResponse;
import com.kesf.backend.service.qa.QaSessionService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/qa")
public class QaController {

    private final QaSessionService qaSessionService;

    @GetMapping("/sessions")
    public ApiResponseDTO<List<QaSessionResponse>> listSessions() {
        return ApiResponseDTO.success(qaSessionService.listSessions());
    }

    @PostMapping("/sessions")
    public ApiResponseDTO<QaSessionResponse> createSession() {
        return ApiResponseDTO.success(qaSessionService.createSession());
    }

    @GetMapping("/sessions/{sessionId}")
    public ApiResponseDTO<QaSessionDetailResponse> getSession(@PathVariable String sessionId) {
        return ApiResponseDTO.success(qaSessionService.getSession(sessionId));
    }

    @DeleteMapping("/sessions/{sessionId}")
    public ApiResponseDTO<QaDeleteSessionResponse> deleteSession(@PathVariable String sessionId) {
        boolean deleted = qaSessionService.deleteSession(sessionId);
        return ApiResponseDTO.success(new QaDeleteSessionResponse(sessionId, deleted));
    }
}
